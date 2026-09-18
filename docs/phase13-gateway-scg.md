# Phase 13：Spring Cloud Gateway 网关实施手册（isegoria-cloud）

> 文档性质：**施工手册（照抄级）**。phase9 回答"要不要上网关"，本文回答"怎么上、代码长什么样、怎么回滚"。
> 与 phase9 的关系：phase9 §6 的**组合 A（SCG，网关独立 Boot 4）**就是本手册的主线；phase9 附录 A 的骨架有 **3 处会静默失效**，已在 §2.1 / §2.2 修正。
> 事实核对时间：2026-09-18（版本号、官方支持周期、上游改名都会变，落地前请重新核对 §2）。
> **本手册只出方案与代码，不动仓库代码** —— 你按章节落地，每阶段都能回滚。

---

## 〇、结论速览

| 问题 | 结论 |
|---|---|
| 用什么形态？ | **SCG 5.x 的 WebFlux（响应式）形态**，artifact 是 `spring-cloud-starter-gateway-server-webflux`（★ 不是老的 `spring-cloud-starter-gateway`，见 §2.1） |
| 端口？ | **8100**（与 phase11/12 预留的一致），Nginx upstream 指过来 |
| 版本？ | **已按车道 B 落地（2026-09-18）**：网关与 6 个业务服务**同栈** —— Boot **3.5.3** + Spring Cloud **2025.0.3** + SCG 4.3.5 + SCA 2025.0.0.0 + Sa-Token **1.41.0**；**业务服务零改动**。工程上：网关的 `<parent>` 就是父聚合 → 版本对齐是结构性的（见 §2.6） |
| 业务服务要改多少？ | **① Sa-Token 1.41.0 → 1.46.0（一行版本号 ×6，为了让会话格式与网关一致）；② P3 阶段 common 加一个 Same-Token 过滤器（可选，配置开关控制）**。其余零改造 |
| 现在有网关吗？ | 目前只有接入层网关（Nginx 按第一段路径直连 6 服务）；上网关后 **Nginx 不废弃，职责上移** |
| 最容易翻车的三处 | ① **SCG 5.x 改了 artifact 名 + 配置前缀，写老写法不报错但路由为空**（§2.1）；② **Sa-Token 版本错配**（1.41.0 没有 boot4 starter，且会话反序列化跨版本无保证，§2.2）；③ **Nacos 里业务服务实例注册的是 Dubbo 端口 50051~50056，不是 HTTP 8201~8206**，盲用 `lb://` 会把 HTTP 打到 Dubbo 端口（§2.4，**P0 第一条必须先验证**） |
| 分几步走？ | **P0 建工程 → P1 纯路由灰度 → P2 加鉴权 → P3 Same-Token → P4 限流/跨域/可观测 → P5 进 Docker 与生产**，每步独立可回滚（§5~§9） |
| 顺带发现的两个既有问题 | ① `RateLimitAspect` 的切点表达式写错 → **当前所有 `@RateLimit` 完全没生效**（§1.4）；② 源码管理端前缀已从 `/api/amin/user` 修为 `/api/admin/user`，但**两份 Nginx 配置还写着 `amin`**（§1.4） |

**先做这三件事，再决定怎么抄**（顺序不能反）：

```
① 看 Nacos 控制台：isegoria-user 的实例端口是 50051 还是 8201？        → 决定 lb:// 能不能用（§2.4）
② 定版本车道：车道 A（网关 Boot 4 + 全栈 Sa-Token 1.46.0，推荐）/ 车道 B（全栈 Boot 3.5.3）→ §2.5
③ 落地后立刻用 /actuator/gateway/routes 验证路由不为空                    → 验证 §2.1 的前缀没写错
```

---

## 一、现状盘点与改造边界

### 1.1 网关接管什么、不接管什么

| 职责 | 现在谁在干 | 上网关后 |
|---|---|---|
| TLS / 静态资源 / gzip / 一级限流 | Nginx | **不动**（Nginx 是接入层，网关是业务层） |
| 路由转发 | Nginx（静态 IP 列表） | **网关**（Nginx 只把 `/api/**` 丢给网关） |
| 登录态粗筛（是否登录） | 6 个服务的 `SaInterceptor` | 网关做粗筛 + **服务内保留**（纵深防御，§6.5） |
| 角色/权限细判（`@SaCheckRole`） | 各服务 | **不动**，网关不该懂业务权限 |
| 限流 | 各服务 `@RateLimit`（★ 当前实际失效） | 网关加一层（Redis 令牌桶），服务内那层**保留**（防绕过） |
| CORS | 各服务 `CorsConfig` | **网关统一处理**，并去重（§8.2） |
| traceId / 访问日志 / 指标 | 无（6 份日志各看各的） | **网关统一注入** |
| 灰度 / AB | 无 | 网关按请求头/权重分流 |
| **业务逻辑、接口聚合** | — | **禁止**。要聚合就另立 BFF 服务（phase9 §10 已经明令） |

### 1.2 对外 URL 分流表（★ 源码实测，网关路由表就以这张表为准）

| # | URL 前缀（含 context-path `/api`） | 归属服务 | Controller 依据 |
|---|---|---|---|
| 1 | `/api/user/**` | user :8201 | `UserController` `@RequestMapping("/user")` |
| 2 | `/api/admin/user/**` | user :8201 | `UserManagerController`（★ 源码现在是 `admin`，不是 `amin`，见 §1.4） |
| 3 | `/api/post/**` `/api/boards/**` `/api/tag/**` `/api/comment/**` `/api/postImage/**` | post :8202 | `PostController` / `BoardController` / `TagController` / `CommentController` / `PostImageController` |
| 4 | `/api/adminPost/**` | post :8202 | `PostManagerController` |
| 5 | `/api/admin/audilog/**` `/api/admin/boards/**` `/api/admin/score-config/**` `/api/admin/sensitive-words/**` `/api/admin/tag/**` | post :8202 | 5 个 `controller/manager/*Controller` |
| 6 | `/api/postLike/**` `/api/collect/**` | interaction :8203 | `LikeController` / `CollectController` |
| 7 | `/api/userFollow/**` `/api/boardFollow/**` | social :8204 | `UserFollowController` / `BoardFollowController` |
| 8 | `/api/notifications/**` | notify :8205 | `NotificationController` |
| 9 | `/api/ws` `/api/ws/**` | notify :8205 | `WebSocketConfig#registerStompEndpoints("/ws")` + SockJS |
| 10 | `/api/search/**` `/api/recommend/**` | rec :8206 | `SearchController` / `RecController` |
| 11 | `/api/admin/search/**` | rec :8206 | `SearchManagerController`（`/reindex`，ES 全量重建） |

**两个路由细节**：

- **`/api/admin/*` 是个"共享第一段"**：user 占 `/api/admin/user`、post 占 5 个子路径、rec 占 `/api/admin/search`。**绝对不要给 post 写 `/api/admin/**` 通配** —— 会把 user 与 rec 的管理端接口一起抢走。路由一律写**到第二段为止的具体前缀** + 显式 `order`。
- **两个 Tag 控制器方法集几乎重复**（`TagController /tag` 与 `TagManagerController /admin/tag` 都有 hot/list/adminList/{id}/add/update/delete），这是既有事实，网关按表中前缀原样分流即可。

### 1.3 本次改造的边界

```
浏览器
  │
  ▼
Nginx（接入层，不动）  TLS / 静态 / gzip / /api/ws 的 Upgrade 透传
  │
  │  /api/**   （全部丢给网关，不再按第一段分 6 条）
  ▼
Spring Cloud Gateway :8100 ★ 本次新增
  │  · 路由（静态 HTTP URI 或 lb://，见 §2.4）
  │  · Sa-Token 登录态粗筛 + 白名单/保护组
  │  · Same-Token 注入（防绕过）
  │  · Redis 令牌桶限流、CORS 去重、traceId
  ▼
user:8201 post:8202 interaction:8203 social:8204 notify:8205 rec:8206
（内部仍走 Dubbo tri 50051~50056，与网关完全无关）
```

**回滚一句话**：Nginx 把 API 段改回直连 6 服务（§5.3 给了整段注释保留）。

### 1.4 ★ 顺带发现的 3 个既有问题（与网关同批处理，否则网关会把坑放大）

**问题 1：`@RateLimit` 当前完全没生效（切点表达式写错）** —— ✅ **已修（2026-09-18）**，现为 `com.ruwei..controller..`

```java
// isegoria-common/src/main/java/com/ruwei/common/web/RateLimitAspect.java:64
@Around("execution(* com.ruwei.controller..*(..))")   // ❌ 全仓不存在 com.ruwei.controller 包
public Object around(ProceedingJoinPoint pjp) throws Throwable {
```

控制器实际在 `com.ruwei.user.controller` / `com.ruwei.post.controller` …（我 grep 过：`^package com\.ruwei\.controller` **零匹配**）。
所以这个切面注册了但**一个方法都织不进去**，`@RateLimit` 是纯装饰。

**修法**（此行建议单独提交，便于回归）：

```java
@Around("execution(* com.ruwei..controller..*(..))")   // ✅ 匹配 com.ruwei.<svc>.controller 及其子包
public Object around(ProceedingJoinPoint pjp) throws Throwable {
```

> 影响：修好之前，"限流失效"这件事只在网关侧体现（网关是第一个真正生效的限流层）。
> 修好后要**复测 `42900`**：`POST /api/post/add` 连发 6 次（`@RateLimit(limit=5, window=60, prefix="post")`）应第 6 次被拒。

**问题 2：管理端前缀 `amin` → `admin` 的改动，Nginx 还没跟上**

```diff
--- isegoria-user/.../UserManagerController.java
-@RequestMapping("/amin/user")
+@RequestMapping("/admin/user")
```

（该修改目前**还在工作区未提交**；`git diff` 可见。）而两份 Nginx 配置里仍然是：

```nginx
location /api/amin/user/ { proxy_pass http://isegoria_user; }   # ❌ 服务端已没有 /amin/user，这条现在必然 404
```

- 若管理端前端目前调的是 `/api/amin/user/...` → **前端要跟着改成 `/api/admin/user/...`**；
- 若暂时不想动前端 → 网关侧可以先用 `RewritePath` 做兼容（**不要**再去服务里恢复 `amin` 映射）：

```yaml
# 兼容期用：/api/amin/user/** → /api/admin/user/**
- id: user-admin-legacy
  uri: http://app-user:8201
  predicates: [ Path=/api/amin/user/** ]
  filters: [ RewritePath=/api/amin/user/(?<seg>.*), /api/admin/user/$\{seg} ]
```

> 无论如何，**落地网关前先把这一项定下来**（前端改 or 网关重写二选一），否则管理端登录会 404 而你会去怀疑网关。

另外：宿主机版 `deploy/nginx/isegoria.conf` **缺** `location /api/admin/search/`（容器版有）→ 生产上 `/api/admin/search/reindex` 走宿主机 Nginx 会 404。网关上线后这条由网关路由统一接管，问题自然消失。

**问题 3：标签接口的「类注释」与「实际注解」不一致；`/user/userInfo` 漏了 `@SaCheckLogin`**

```java
// isegoria-post/.../controller/TagController.java
/** <p>查询接口公开（无需登录）；增删改属平台管理行为，标注 @SaCheckRole("admin")…</p> */   // ← 注释这么写
@RestController
@RequestMapping("/tag")
@SaCheckLogin          // ← 实际是"整类都要登录"，与注释矛盾
public class TagController {
    @GetMapping("/hot")   @SaIgnore      // 只有这一个真公开
    @GetMapping("/list")                 // ← 实际需要登录
    @GetMapping("/{id}")                 // ← 实际需要登录
```

- 实测后果：**游客调 `GET /api/tag/list` / `GET /api/tag/{id}` 会拿到 `40100`**（`TagManagerController` 同理，仅 `/hot` 公开）；
- 若产品口径是"标签列表要登录" → 请把类注释改对（现在就写错了，会误导后续维护）；
- 若产品口径是"游客也能看标签列表" → 给 `/list`、`/{id}` 补 `@SaIgnore`；
- **不论哪种结论，网关的公开清单都按"代码实测"写**（落地文件已按代码为准，避免上线后游客端 401）。

`GET /api/user/userInfo`（前台）与 `GET /api/admin/user/userInfo`（管理端）**没有 `@SaCheckLogin`**，旁边注释却写着"@SaCheckLogin 已保证登录态" —— 它们靠内部 `StpUtil.getLoginIdAsLong()` 抛 `NotLoginException` 兜住，游客行为上仍是 40100，但注解是漏的，建议补上（顺带让"网关是否要粗筛"这件事有明确依据）。

> 这三条都不影响网关能不能跑，但**都会在"配白名单/保护组"时把你带偏**，所以放在一起处理最省事。

---

## 二、⚠️ 落地前必须核对的四件事（版本 / 兼容性）

> 这一节是全文最重要的部分。**四件事不定，代码写完了也跑不起来。**

### 2.1 SCG 5.x 改名了：artifact 与配置前缀（★ 静默失效）

Spring Cloud 2025.0.0 起，Gateway 的 starter 与配置前缀都被重命名（2025.1.x / Gateway 5.x 延续）：

| | 旧（Gateway 4.x 及以前，phase9 附录 A 的写法） | 新（SC 2025.0.x / 2025.1.x） |
|---|---|---|
| WebFlux starter | `spring-cloud-starter-gateway` | **`spring-cloud-starter-gateway-server-webflux`** |
| WebMVC starter | `spring-cloud-starter-gateway-mvc` | `spring-cloud-starter-gateway-server-webmvc` |
| 路由/跨域配置前缀 | `spring.cloud.gateway.routes` / `.globalcors` | **`spring.cloud.gateway.server.webflux.routes` / `.globalcors`** |
| MVC 配置前缀 | `spring.cloud.gateway.mvc.*` | `spring.cloud.gateway.server.webmvc.*` |

**为什么要单独写一节**：写老坐标 → 类找不到（会报错，好排查）；写老**前缀** → **不报任何错，`/actuator/gateway/routes` 返回 `[]`，请求全部落到静态处理器 404**。这是 Gateway 5 升级后最常见的一类"配置看起来没问题但路由就是加载不到"。

**自查命令**（P0 起来第一件事就跑）：

```bash
curl -s 127.0.0.1:8100/actuator/gateway/routes | python -m json.tool | head -40
# 期望：能看到 user/post/... 一条条路由；若为 [] → 前缀写错了

# 顺带核对属性前缀真的绑定了（新版是 server.webflux）
curl -s 127.0.0.1:8100/actuator/configprops | grep -o '"prefix":"spring.cloud.gateway[^"]*"' | sort -u
```

**兜底工具**：如果某几个 `spring.cloud.gateway.*` 子配置（如 `httpclient.*` 超时、`trusted-proxies`）你拿不准新版全路径，加一个临时依赖，启动日志会把废弃前缀逐条打出来：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-properties-migrator</artifactId>
    <scope>runtime</scope>
</dependency>
```

> 官方文档里"Http timeouts configuration"页面目前仍展示 `spring.cloud.gateway.httpclient.*` 的老写法（文档滞后于 5.x 改名），所以**以 `configprops` 的实际输出为准**，别照抄文档。

**★ 坐标核验记录（2026-09-18，直接拉 Maven Central 的 POM 逐个核对，别猜）**：

| 核验项 | 结果 |
|---|---|
| `org.springframework.boot:spring-boot-starter-parent:4.0.8` | ✅ 存在 |
| `org.springframework.cloud:spring-cloud-dependencies:2025.1.3` | ✅ 存在；其中 `spring-cloud-gateway.version = **5.0.3**`，via import `spring-cloud-gateway-dependencies` |
| `spring-cloud-gateway-dependencies:5.0.3` 是否管 starter 版本 | ✅ 管 `spring-cloud-starter-gateway-server-webflux`（= `${project.version}` 5.0.3）与 `…-server-webmvc` |
| 老的 `spring-cloud-starter-gateway` 在新 BOM 里 | ❌ **未托管** → 用旧坐标必须自带 version，且它最新只到 4.3.5（属 SC 2025.0.x 线） |
| `com.alibaba.cloud:spring-cloud-alibaba-dependencies:2025.1.0.0` | ✅ 存在；管到 `spring-cloud-starter-alibaba-nacos-discovery:2025.1.0.0` |
| `cn.dev33:sa-token-dependencies:1.46.0` | ✅ 存在（**薄壳**：只 import `cn.dev33:sa-token-bom:1.46.0`） |
| `sa-token-bom:1.46.0` 是否管我们用的三个 artifact | ✅ `sa-token-reactor-spring-boot4-starter` / `sa-token-redis-jackson` / `sa-token-core` 全部 = 1.46.0（**所以 pom 里不写 version 是正确的**） |
| `sa-token-bom:1.41.0` 有没有 boot4 starter | ❌ 没有（只有 `…-reactor-spring-boot-starter` 与 `…-boot3-starter`）→ 印证 §2.2 |
| `com.alibaba.nacos:nacos-client:2.4.2` | ✅ 存在 |

**★ 更进一步：把 jar 拉下来逐字节核对（不靠文档、不靠记忆）**。下面这些结论都是本轮实测：

| 核验项 | 结果 |
|---|---|
| 配置前缀到底是不是 `spring.cloud.gateway.server.webflux.*` | ✅ `spring-cloud-gateway-server-webflux-5.0.3.jar` 的 `META-INF/spring-configuration-metadata.json` 里 groups 前缀就是 `spring.cloud.gateway.server.webflux`（含 `.httpclient`、`.httpclient.websocket`、`.globalcors`、`.x-forwarded`、`.redis-rate-limiter`、`.filter.request-rate-limiter`）→ 本文 §4.4 的写法成立 |
| 老前缀 `spring.cloud.gateway.routes` 会不会报错 | ❌ 不报错、不绑定（元数据里没有 `spring.cloud.gateway.*` 这一层）→ 静默为空，必须写新前缀 |
| `trusted-proxies` 是否真存在 | ✅ 元数据里 `spring.cloud.gateway.server.webflux.trusted-proxies` (String) |
| `httpclient.websocket.max-frame-payload-length` | ✅ 存在（另有 `proxy-ping`） |
| 限流过滤器名 / 参数名 / 桶 key | 见 §8.1（`RequestRateLimiter` + camelCase 参数；key 含 routeId） |
| **Boot 4 的包移动**（照抄 Boot 3 文章会编译不过） | `ErrorWebExceptionHandler`、`ErrorAttributes` 从 Boot 3 的 `org.springframework.boot.web.reactive.error` 挪到 **`org.springframework.boot.webflux.error`**；`ErrorAttributeOptions` 仍在 `org.springframework.boot.web.error`。`NotFoundException` 仍在 `org.springframework.cloud.gateway.support`（已核对 SCG 5.0.3 的 jar） |
| 网关 9 个 Java 文件的 import 是否都能落地 | ✅ 逐条比对了 spring-framework **7.0.9** / spring-boot **4.0.8** / jackson **2.21.5** / reactor-core **3.8.7** / spring-data-redis **4.0.7** / sa-token-core **1.46.0** / SCG **5.0.3** 的真实 jar，无一条落空（脚本：`.workbuddy/_check_gateway_imports.py`） |
| **Boot 4 的 JSON 换成了 Jackson 3**（最容易踩且最难搜的一坑） | `spring-boot:4.0.8` 的模块列表里 Jackson 被拆成 `spring-boot-jackson`（**Jackson 3 / `tools.jackson`**，WebFlux starter 默认带它）与 `spring-boot-jackson2`（**Jackson 2 兼容模块，默认不带**）。实测：`spring-boot-jackson` 的 POM 里**没有** `spring-boot-jackson2`；而 `Jackson2AutoConfiguration$JacksonObjectMapperConfiguration` 才是定义 `com.fasterxml.jackson.databind.ObjectMapper` Bean 的地方 → **不显式加 `spring-boot-jackson2`，容器里就没有 Jackson 2 的 ObjectMapper Bean**，`GatewayErrorConfig` 构造注入会启动失败 |
| `SaSameUtil` 的可用方法（1.46.0） | 有 `getToken()` / `refreshToken()` / `checkToken(String)` / `checkCurrentRequestToken()` / `SAME_TOKEN`；**没有 `getTokenTimeout()` / `getTimeout()`** → 别照网上的"剩余不足 X 秒才刷新"写法（编译不过）。要按 TTL 判断只能自己 `SaManager.getSaTokenDao().getTimeout(key)` |
| Sa-Token Reactor 版的类归属 | `SaReactorFilter` 在 **`sa-token-reactor-spring-boot-starter`**（boot4 那个 starter 是薄适配层：只依赖 `sa-token-spring-boot-reactor-v3v4-common` + `sa-token-jackson3` + 自己的 BOM）→ 依赖只要引 boot4 starter 即可，传递依赖会把实现带进来 |
| `SaRouter.match(List<String>)` 重载是否存在 | ✅ 存在（`SaRouter.class` 里有描述符 `(Ljava/util/List;)Lcn/dev33/satoken/router/SaRouterStaff;`，`SaRouterStaff.notMatch` 同样有 List 重载）→ 手册里 `SaRouter.match(protectedPaths).notMatch(publicPaths)` 的写法能编译 |

> 做法备忘：这些结论来自"下载 jar → 解 `META-INF/spring-configuration-metadata.json` / 扫 class 常量池"，
> 比翻官网（文档常滞后于改名）和问模型（会按 Boot 3 记忆回答）都可靠。脚本都留在 `.workbuddy/` 下可复用。

**★ 车道 B（Boot 3.5.3 + SC 2025.0.3）定稿时的补充核验（2026-09-18）**：

| 核验项 | 结果 |
|---|---|
| SC 2025.0.3 → gateway 版本 | **4.3.5** |
| 新坐标 starter 在 2025.0.x 是否可用 | ✅ `spring-cloud-gateway-dependencies:4.3.5` 同时托管新旧四个名字；`spring-cloud-starter-gateway-server-webflux:4.3.5` → **marker 包**（14 个条目）→ 依赖 `spring-cloud-gateway-server`（真正实现，492 个 class） |
| **新配置前缀在 2025.0.x 是否也生效**（决定 yml 要不要改） | ✅ **生效**：`spring-cloud-gateway-server-4.3.5.jar` 元数据里有 **19 个** `spring.cloud.gateway.server.webflux.*` 组、**老前缀组为 0** → 与 5.x 完全一致，**application.yml 一行都不用改** |
| `trusted-proxies` / `httpclient.response-timeout` / `httpclient.websocket.max-frame-payload-length` / `globalcors` / `discovery.locator.enabled` | ✅ 4.3.5 全部存在 |
| 限流过滤器名与参数 | ✅ `RequestRateLimiterGatewayFilterFactory` 存在；`RedisRateLimiter$Config` 仍是 camelCase；全局限流配置（`...filter.request-rate-limiter.default-key-resolver` 等）4.3.5 也有 |
| Boot 3.5.3 的 Jackson | ✅ 自带 `JacksonAutoConfiguration`（注册 Jackson 2 的 `ObjectMapper` Bean）→ **不需要** `spring-boot-jackson2` |
| Boot 3.5.3 的错误处理类 | ✅ `ErrorAttributes` / `ErrorWebExceptionHandler` 在 `org.springframework.boot.web.reactive.error`（**Boot 4 才**挪到 `webflux.error`） |
| Sa-Token 1.41.0 能否满足网关 | ✅ `sa-token-reactor-spring-boot3-starter:1.41.0` 内含 `SaReactorFilter`；`SaSameUtil` 有 getToken/refreshToken/checkToken/checkCurrentRequestToken；`SaRouter.match(List)` 重载在 1.41.0 就**已存在** |
| 9 个 Java 文件的 import | ✅ 逐条对 Boot **3.5.3** / spring-framework **6.2.8** / jackson **2.19.1** / reactor-core **3.7.7** / spring-data-redis **3.5.1** / sa-token-core **1.41.0** / SCG **4.3.5** 的真实 jar 比对，无一条落空 |

自查命令（本地跑一次就能确认整个 pom 可解析）：

```bash
mvn -f isegoria-gateway/pom.xml -U -DskipTests clean package
# 或只想校验依赖图不编译：
mvn -f isegoria-gateway/pom.xml -U dependency:resolve
```

### 2.2 Sa-Token 版本必须和业务服务"逐字一致"（★ 会话反序列化）

> ✅ **本项目最终决定（2026-09-18）：走下面的路线 B —— 不升 Sa-Token。**
> 网关与 6 个业务服务同栈（Boot 3.5.3 + Sa-Token 1.41.0），**业务服务零改动**，会话格式天然一致。
> 下表保留作为决策依据备查；将来要升 Boot 4 时按**路线 A**"两端一起升"（差异见附录 D）。

现状与约束（都是硬事实）：

| 事实 | 说明 |
|---|---|
| 业务服务用 `sa-token-spring-boot3-starter` + `sa-token-redis-jackson` **1.41.0** | 版本由父 pom 的 `sa-token-dependencies` BOM 管（`isegoria-common/pom.xml` 引入） |
| `sa-token-reactor-spring-boot4-starter` **只有 1.45.0 / 1.46.0**（GitHub / Maven Central） | 1.41.0 那一版 Boot 4 还没 GA，**根本不存在 boot4 包** |
| 会话存在**同一个 Redis**，用 Jackson 序列化 `SaSession` | 网关要"读得到"登录态，就必须反序列化业务服务写进去的对象 |

**结论：网关要与业务服务同版本。而 Boot 4 要求 Sa-Token ≥ 1.45。所以要么升级业务服务，要么放弃 Boot 4。** 三条路线：

| 路线 | 组合 | 优点 | 代价 | 适用 |
|---|---|---|---|---|
| **A（本手册主线）** | 网关 Boot 4.0.8 + Sa-Token **1.46.0**；6 个业务服务 Sa-Token 也升到 **1.46.0**（Boot 仍 3.5.3） | 网关落在**支持期内**的 release train；两边版本一致，会话无歧义 | 6 个 pom 改一行 + 一轮回归；**升级瞬间 Redis 里的旧会话全部失效（用户需重登一次）**；`sa-token-redis-jackson` 在 1.46 起用 Redis 6.0+ 的 `SET KEEPTTL`（本项目 Redis 7 ✓） | 长期维护、不想一上线就踩过保版本 |
| **B（快速落地）** | 网关 Boot **3.5.3** + Spring Cloud **2025.0.3** + `sa-token-reactor-spring-boot3-starter` **1.41.0**；业务服务零改动 | 改动面最小，Sa-Token 天然对齐 | 落在 **OSS 支持已于 2026-06-30 结束**的 2025.0.x 上（无免费 CVE 修复；2025.0.3 是该线最后一版） | 先跑通、后升级；短期交付 |
| **C（零耦合过渡）** | 网关**不引 Sa-Token**，鉴权全部留在服务内；网关只做路由 / 限流 / CORS / traceId | 版本完全解耦，业务服务一行不改 | 拿不到"网关挡住未登录流量"的收益；`Same-Token` 也暂时不需要（因为没有网关鉴权可绕过） | 先要灰度与可观测性，暂不想动鉴权 |

**车道 A 的 6 处服务侧改动（只有一行）**：

```diff
--- pom.xml (isegoria-cloud 父 POM)
-        <sa-token.version>1.41.0</sa-token.version>
+        <sa-token.version>1.46.0</sa-token.version>
```

```bash
# 升级后必须回归（Sa-Token 1.41 → 1.46 的 API 变化极小，但要确认）
# ① 登录 → 6 个服务都能取到登录态（StpUtil.getLoginIdAsLong 不为 null）
# ② @SaCheckRole("admin") 仍生效（user / post 的 StpUtilInterfaceImpl）
# ③ 重启后新会话写入、旧会话清空（先 FLUSHDB 或接受一次全员重登）
```

> ⚠️ **不要试图"网关用 1.46、服务用 1.41"**。SaSession 的 Jackson 序列化跨小版本没有兼容承诺，症状是"用户明明登录成功，网关却一直 401"，且日志里只有反序列化异常，非常难查。要么同版本，要么别让网关碰会话（路线 C）。

### 2.3 Nacos 客户端 vs 服务端（SCA 会带进 3.x 客户端）

| 组件 | 版本 | 兼容性 |
|---|---|---|
| 本项目 Nacos **服务端**（compose） | **2.3.2** | 官方兼容表：2.x 服务端支持 **1.2.0 ~ 2.x 客户端** |
| SCA `spring-cloud-starter-alibaba-nacos-discovery` 2025.1.0.0 传递的 `nacos-client` | **3.1.1** | Nacos 3.x 服务端才"2.x / 3.x 客户端都兼容"，**服务端 2.3.2 + 3.x 客户端属未声明支持的组合** |
| 业务服务侧的 `nacos-client`（父 pom 管理） | **2.4.2** | ✓（Dubbo 用） |

**处置（二选一）**：

```xml
<!-- 方案① （推荐，改动小）：网关把 nacos-client 钉到与业务服务一致的 2.4.2 -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
    <exclusions>
        <exclusion>
            <groupId>com.alibaba.nacos</groupId>
            <artifactId>nacos-client</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>com.alibaba.nacos</groupId>
    <artifactId>nacos-client</artifactId>
    <version>2.4.2</version>
</dependency>
```

```yaml
# 方案②（彻底）：把 Nacos 服务端升到 3.1.x（官方声明同时兼容 2.x / 3.x 客户端）
#   代价：Nacos 3.x 控制台端口改为 8080；鉴权相关环境变量与 2.x 有差异；
#         6 个业务服务的 Dubbo 注册要整体回归一次。本手册不展开。
```

**验证点**（方案①）：网关启动日志无 `NacosException`，控制台「服务管理 → 服务列表」出现 `isegoria-gateway`。

### 2.4 ★ 最重要的一条：Nacos 里业务服务的"实例端口"是 HTTP 还是 Dubbo？

本项目 6 个服务的 yml 统一是：

```yaml
dubbo:
  application:
    name: isegoria-user
    register-mode: instance      # 应用级注册：以 **dubbo.application.name** 为 Nacos 服务名注册实例
  protocol:
    name: tri
    port: 50051                  # 应用暴露的 Dubbo 端口
```

**Dubbo 应用级注册写进 Nacos 的是「Dubbo 协议端口」，不是 Spring Boot 的 HTTP 端口。** 即 Nacos 里极可能是：

```
isegoria-user        192.168.x.x:50051   ← Dubbo tri 端口（Dubbo 注册的）
isegoria-post        192.168.x.x:50052
...（而 HTTP 是 8201~8206，Nacos 里没有）
```

**这直接决定网关路由怎么写**：

| Nacos 里 `isegoria-user` 的端口 | 含义 | 路由写法 |
|---|---|---|
| **8201**（HTTP） | 有 Spring Cloud 侧注册，可直接动态发现 | `uri: lb://isegoria-user` ✅ 推荐，享受动态扩缩容 |
| **50051**（Dubbo tri） | 只有 Dubbo 注册 | ⚠️ **不能用 `lb://`** —— 会把 HTTP 请求打到 tri 端口。见下面三条路 |

**P0 第一条验证（现在就去看）**：Nacos 控制台 → 服务管理 → 服务列表 → 点开 `isegoria-user` → 看实例端口。

如果是 50051，三条路选一条：

1. **静态 URI（本手册主线，零额外改动）**：
   ```yaml
   uri: http://app-user:8201        # docker compose 内用服务名
   # 或 uri: http://127.0.0.1:8201  # 宿主机部署（java -jar + 宿主机 Nginx）
   ```
   代价：实例扩缩容要改配置（与现在 Nginx upstream 一样），**失去 SCG 的最大卖点**。适合先跑通。
2. **让业务服务注册 HTTP 实例，且与 Dubbo 实例错开服务名**（推荐用于"真要用动态发现"）：

   ```yaml
   # 加到 6 个业务服务的 yml（新增依赖 spring-cloud-starter-alibaba-nacos-discovery）
   spring:
     cloud:
       nacos:
         discovery:
           server-addr: ${NACOS_HOST:nacos}:8848
           username: ${NACOS_USER:nacos}
           password: ${NACOS_PASSWORD}
           service: isegoria-user-http   # ★★ 关键：不要用默认的 spring.application.name！
   ```
   ```yaml
   # 网关侧改用
   uri: lb://isegoria-user-http
   ```
   **为什么必须改服务名**：若 SCA 用默认名 `isegoria-user` 注册，Nacos 里同一个服务名下会同时存在 `:50051`（Dubbo）与 `:8201`（HTTP）两个健康实例 → `lb://` 会**一半请求打到 tri 端口**，表现为"每隔一次请求失败"（最难查的一类故障）。
3. **升级 Nacos + 统一走 SCA**：改动面最大，除非要上 K8s，否则不做。

> **本手册后续章节默认用第 1 条（静态 URI）**，因为它零风险、与现有部署形态（compose 服务名固定）完全匹配；第 2 条作为 §9.6 的"动态发现升级"单独给出。

### 2.5 版本矩阵定案（★ **车道 B**，2026-09-18 最终选定）

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | **17** | Boot 3.5 与 Boot 4 都满足 |
| Spring Boot（网关） | **3.5.3** | **与 6 个业务服务完全一致**（由父聚合 POM 统一管） |
| Spring Cloud BOM | **2025.0.3** | Northfields 最后一版（OSS 支持 2026-06-30 已结束）；gateway 4.3.5 |
| Spring Cloud Gateway | 4.3.5（由 BOM 管） | artifact 仍用新名 `spring-cloud-starter-gateway-server-webflux`（旧名也还在，但新名是迁移目标） |
| Spring Cloud Alibaba | **2025.0.0.0** | 适配 SC 2025.0.x / Boot 3.5.x；`nacos-client` 按 §2.3 钉到 2.4.2 |
| Sa-Token | **1.41.0** | **与业务服务逐字一致**（父 POM 的 BOM 决定）→ 共享 Redis 的 `SaSession` 序列化格式天然一致；网关用 `sa-token-reactor-spring-boot3-starter` |
| Redis | 7.x（已就绪） | 必须与业务服务**同一个 Redis、同一个 database** |
| Nacos | 2.3.2（已就绪） | 只用作服务发现（可选）；Dubbo 侧不动 |

**为什么最终选 B 而不是 A（Boot 4）**：

| | 车道 A（Boot 4） | **车道 B（已选，Boot 3.5.3）** |
|---|---|---|
| 业务服务改动 | 必须把 Sa-Token 从 1.41.0 升到 1.46.0（boot4 starter 起于 1.45）+ 一轮回归 + 一次全员重登 | **零改动** |
| 会话兼容 | 靠"两边一起升到同版本"来保证 | **结构性保证**（同一个 BOM） |
| 支持周期 | 在支持期内（2027-07-31） | ⚠️ 2025.0.x 的 OSS 支持已于 2026-06-30 结束 |
| 升级路径 | — | 将来要升 Boot 4 时按 §2.2 路线 A **两端一起升**，见附录 D |

> 车道 A 的全套差异（5 处）保留在 **附录 D**，将来升级照它做即可。
> 本文档中凡标注"Boot 4 / 5.x 专属"的坑（Jackson 3、包路径移动等）在车道 B 下**不存在**，但升级时必须回看。

### 2.6 版本对齐是"结构性"的（车道 B 的关键设计）

网关的 `<parent>` **就是父聚合 `com.ruwei:isegoria-cloud`**，因此：

```
父 pom <properties>  →  spring-boot.version=3.5.3 / sa-token.version=1.41.0 / nacos-client.version=2.4.2
        ▲
        ├── isegoria-common / user / post / …（业务服务）
        └── isegoria-gateway          ← 同一套版本，一个字都没写死
```

含义：**升 Sa-Token 时不可能漏改一边**（这正是"网关读不到会话"最常见的成因）。
网关自己只声明两个版本：`spring-cloud.version` 与 `spring-cloud-alibaba.version`（父 POM 不含 Spring Cloud）。
自检脚本会检查"网关没有自定义 sa-token.version / nacos-client.version"这条约束。

---

## 三、架构定位与拓扑

**一张图记住三条边界**：

```
┌──────────────────────────────────────────────────────────┐
│ Nginx（接入层）                                            │
│   TLS 终结 · 静态资源 · gzip · client_max_body_size 12m    │
│   /api/ws 的 Upgrade 透传 + proxy_read_timeout 3600s       │
└───────────────────────┬──────────────────────────────────┘
                        │  upstream isegoria_gateway → 127.0.0.1:8100
                        ▼
┌──────────────────────────────────────────────────────────┐
│ isegoria-gateway :8100（业务网关，本手册新增）              │
│   ① 路由（写死到第二段的具体前缀，见 §4.5）                 │
│   ② Sa-Token 登录态粗筛（会话读同一个 Redis；token 继续透传）│
│   ③ Same-Token 注入（SA-SAME-TOKEN 头）——防绕过             │
│   ④ Redis 令牌桶限流 · CORS 统一 + 去重 · traceId          │
│   ⑤ 不写任何业务逻辑                                        │
└──────┬────────┬────────┬────────┬────────┬────────┬───────┘
       ▼        ▼        ▼        ▼        ▼        ▼
    user     post   interaction  social  notify    rec      （HTTP 8201~8206）
                                                    │
                                          WebSocket /api/ws（SockJS + STOMP）
```

**启动顺序**：中间件（Redis/Nacos）→ **gateway** → user → post → interaction → social → notify → rec。
网关不依赖 MySQL/MQ/ES/PG，只依赖 Redis（Sa-Token 会话 + 限流）与 Nacos（可选）。

---

## 四、P0：建网关工程（照抄级）

### 4.1 工程结构：**列进 `<modules>` 并继承父 POM**（车道 B 的必然选择）

```
isegoria-cloud/
├── pom.xml                    ← 父聚合：Boot 3.5.3 + sa-token 1.41.0 + nacos-client 2.4.2 都在这里
│                                 ★ <modules> 里有 isegoria-gateway（构建）且它就是网关的 <parent>（版本继承）
├── isegoria-common/ model/ client/ user/ post/ …      ← 6 个业务服务
└── isegoria-gateway/          ← ★ 网关
    ├── pom.xml                ← parent = com.ruwei:isegoria-cloud（relativePath ../pom.xml）
    └── src/main/java/com/ruwei/gateway/…
```

**先分清 Maven 的两个概念（升级到 Boot 4 时会用到另一半）**：

| | 靠什么生效 | 效果 |
|---|---|---|
| 聚合 `<modules>` | 把模块放进 reactor | 决定构建顺序；**不做任何配置继承** |
| 继承 `<parent>` | 子模块从 parent 取 `dependencyManagement` / `properties` / `pluginManagement` | 版本、插件、编码全跟着走 |

**车道 B（当前）：两者都要 —— 进 `<modules>` 且 `<parent>` 指向父聚合。**
好处是上表右列的"版本继承"正好是我们要的：`sa-token.version` / `spring-boot.version` / `nacos-client.version`
全由父 POM 一处决定，**网关与 6 个业务服务不可能版本错配**（§2.6）。同时 IDEA 能把网关当 Maven 工程导入
（pom 不"识别不到"）、`mvn -pl isegoria-gateway` 可用、根目录 `mvn package` 会一起构建。

**将来升 Boot 4（车道 A）时，才需要把"继承"切开**：那时网关要 Boot 4.0.x，而业务服务仍在 3.5.x，
必须让网关的 `<parent>` 换成外部 `spring-boot-starter-parent:4.0.8` + 显式 `<relativePath/>`
（空值 = 不去 `..` 找 parent），并自带 SC / SCA / Sa-Token 三个 BOM import。
**只切继承、不动 `<modules>`** —— 这样 IDE 识别与统一构建都不受影响。

> ⚠️ **副作用（车道 B 也有）**：网关进了 reactor，`mvn package` / `docker compose build` 里
> **任一模块编译失败都会整体失败**。若某次要紧急发业务服务而网关正好在改，用 `-pl` 跳过：
> `mvn -B -DskipTests -pl isegoria-user,isegoria-post,isegoria-interaction,isegoria-social,isegoria-notify,isegoria-rec -am package`
>
> 📌 版本沿革：本文早先写过"不能加进父聚合 `<modules>`"（当时网关是 Boot 4、要避免继承到 Boot 3.5.3 的版本树），
> 以及"进 modules 但 parent 用外部 Boot"——**车道 B 下这两条都不适用**，此处为准。

### 4.2 `isegoria-gateway/pom.xml`（全文）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- ★★ 车道 B：parent 就是父聚合 → 直接继承 Boot 3.5.3 / sa-token 1.41.0 / nacos-client 2.4.2
         （"升 Sa-Token 时两边一起升"因此是结构性保证，见 §2.6） -->
    <parent>
        <groupId>com.ruwei</groupId>
        <artifactId>isegoria-cloud</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>isegoria-gateway</artifactId>
    <name>isegoria-gateway</name>
    <description>IsegoriaForum 业务网关（Spring Cloud Gateway / WebFlux）</description>

    <properties>
        <!-- ★ 与业务服务同一代：2025.0.x（配套 Boot 3.5.x）。
             2025.0.3 是该 release train 最后一个 OSS 版本（OSS 支持 2026-06-30 结束）。 -->
        <spring-cloud.version>2025.0.3</spring-cloud.version>
        <!-- SCA 2025.0.0.0 适配 SC 2025.0.x / Boot 3.5.x（传递 nacos-client 3.0.3 → 见下面的 exclude） -->
        <spring-cloud-alibaba.version>2025.0.0.0</spring-cloud-alibaba.version>
        <!-- ⚠️ 这里**故意不写** sa-token.version / nacos-client.version / java.version：
             全部继承父 POM，避免"网关一套、业务服务一套"（会话读不到的最常见成因） -->
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.alibaba.cloud</groupId>
                <artifactId>spring-cloud-alibaba-dependencies</artifactId>
                <version>${spring-cloud-alibaba.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <!-- 其余（spring-boot-dependencies 3.5.3 / sa-token-dependencies 1.41.0 /
                 nacos-client 2.4.2 / hutool / knife4j …）全部继承自父 POM -->
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- ===== 网关本体（★ 5.x 的新坐标；老坐标 spring-cloud-starter-gateway 已废） ===== -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
        </dependency>
        <!-- lb:// 负载均衡（用静态 URI 时也建议留着，将来切 lb:// 不用改 pom） -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>

        <!-- ===== 服务发现：Nacos（nacos-client 钉到 2.4.2，见 §2.3） ===== -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
            <exclusions>
                <exclusion>
                    <groupId>com.alibaba.nacos</groupId>
                    <artifactId>nacos-client</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
        <dependency>
            <groupId>com.alibaba.nacos</groupId>
            <artifactId>nacos-client</artifactId>
            <!-- 版本继承父 POM（2.4.2），此处**不写** -->
        </dependency>

        <!-- ===== Sa-Token（Reactor 版 + 与业务服务同版本 / 同款 Redis 序列化） ===== -->
        <dependency>
            <groupId>cn.dev33</groupId>
            <artifactId>sa-token-reactor-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>cn.dev33</groupId>
            <artifactId>sa-token-redis-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-pool2</artifactId>
        </dependency>

        <!-- ===== 限流：SCG 的 Redis 令牌桶需要「响应式」的 Redis 客户端 ===== -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
        </dependency>

        <!-- ===== 运维：健康检查 + Prometheus 指标 ===== -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>

        <!-- ===== ★ Jackson：车道 B（Boot 3.5.3）**不需要**额外依赖 =====
             Boot 3.5 自带 Jackson 2，JacksonAutoConfiguration 会注册
             com.fasterxml.jackson.databind.ObjectMapper 这个 Bean，GatewayErrorConfig 直接注入即可。
             ⚠️ 若将来升到 Boot 4：JSON 默认切到 Jackson 3（tools.jackson），Jackson 2 支持被拆到
                独立模块 spring-boot-jackson2（WebFlux starter **不会**带它）→ **必须补上这个依赖**，
                否则启动报 required a bean of type '...ObjectMapper' that could not be found（手册 §2.1）。 -->

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

> ⚠️ **不要引 `spring-boot-starter-web`**（Servlet 栈）。一旦 Servlet 栈进来，应用会以 SERVLET 模式启动，**SCG 的 WebFlux 自动配置不注册路由基础设施**，`/actuator/gateway/routes` 永远 `[]`（这也是 §2.1 之外另一类"路由为空"的成因）。WebSocket 客户端栈不用管，SCG 自带。

### 4.3 启动类

`isegoria-gateway/src/main/java/com/ruwei/gateway/IsegoriaGatewayApplication.java`

```java
package com.ruwei.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IsegoriaForum 业务网关启动类（Spring Cloud Gateway 5.x / WebFlux）。
 *
 * <p>职责边界：只做横切（路由 / 登录态粗筛 / Same-Token 注入 / 限流 / CORS / traceId），
 * <b>严禁写业务逻辑与接口聚合</b>（要聚合另立 BFF 服务）。</p>
 *
 * @author ruwei
 */
@SpringBootApplication
@EnableScheduling   // SameTokenRefreshJob 需要
public class IsegoriaGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(IsegoriaGatewayApplication.class, args);
    }
}
```

### 4.4 `application.yml`（全文，本地开发）

`isegoria-gateway/src/main/resources/application.yml`

```yaml
server:
  port: 8100

spring:
  application:
    name: isegoria-gateway
  # ★★ 必须是 reactive：以防将来误引 servlet 依赖导致 SCG 自动配置不生效
  main:
    web-application-type: reactive
  # ★★ 必须与业务服务指向**同一个 Redis、同一个 database**（Sa-Token 会话共享）
  data:
    redis:
      host: localhost
      port: 6379
      password:
      database: 0
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0

  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        username: nacos
        password: nacos
        register-enabled: true          # 让网关注册自己，Nacos 列表可见 = 一个人工探活点

    gateway:
      server:
        webflux:                        # ★ SC 2025.0.0+ 的新前缀（老写法 spring.cloud.gateway.* 静默失效）
          discovery:
            locator:
              enabled: false            # ★ 保持 false：开它会按服务名自动生成路由，等于把内部端点全暴露
              lower-case-service-id: true
          # 响应头去重（★ P4 会用到：网关与业务服务都可能写 CORS 头）
          default-filters:
            - DedupeResponseHeader=Access-Control-Allow-Credentials Access-Control-Allow-Origin, RETAIN_FIRST
          routes:
            # ---------- ★ order 决定匹配优先级：越具体的越靠前 ----------
            # 1) user 管理端（必须在任何 /api/admin/** 通配之前；本表刻意不用 admin 通配）
            - id: user-admin
              order: 10
              uri: http://127.0.0.1:8201
              predicates:
                - Path=/api/admin/user/**
            # 2) user 前台
            - id: user
              order: 20
              uri: http://127.0.0.1:8201
              predicates:
                - Path=/api/user/**
            # 3) post 前台
            - id: post
              order: 30
              uri: http://127.0.0.1:8202
              predicates:
                - Path=/api/post/**,/api/boards/**,/api/tag/**,/api/comment/**,/api/postImage/**
            # 4) post 管理端（逐个列具体前缀，**不要写 /api/admin/** 通配**）
            - id: post-admin
              order: 40
              uri: http://127.0.0.1:8202
              predicates:
                - Path=/api/adminPost/**,/api/admin/audilog/**,/api/admin/boards/**,/api/admin/score-config/**,/api/admin/sensitive-words/**,/api/admin/tag/**
            # 5) rec 管理端（运维接口；放在 post-admin 之后也无妨，前缀不重叠）
            - id: rec-admin
              order: 50
              uri: http://127.0.0.1:8206
              predicates:
                - Path=/api/admin/search/**
            # 6) interaction
            - id: interaction
              order: 60
              uri: http://127.0.0.1:8203
              predicates:
                - Path=/api/postLike/**,/api/collect/**
            # 7) social
            - id: social
              order: 70
              uri: http://127.0.0.1:8204
              predicates:
                - Path=/api/userFollow/**,/api/boardFollow/**
            # 8) notify（普通接口）
            - id: notify
              order: 80
              uri: http://127.0.0.1:8205
              predicates:
                - Path=/api/notifications/**
            # 9) WebSocket（★ 单独一条，去掉全局响应超时，否则长连接会被掐断）
            - id: notify-ws
              order: 90
              uri: http://127.0.0.1:8205
              predicates:
                # 精确 /api/ws 与 /api/ws/** 都要：SockJS 会请求 /api/ws/info、/api/ws/{s}/{id}/websocket
                - Path=/api/ws,/api/ws/**
              metadata:
                response-timeout: -1        # ★ 负值 = 禁用全局 response-timeout（WS 必须）
                connect-timeout: 5000
            # 10) rec：搜索 + 推荐
            - id: rec
              order: 100
              uri: http://127.0.0.1:8206
              predicates:
                - Path=/api/search/**,/api/recommend/**

          # 跨域统一在网关处理（P4 生效；业务服务的 CorsConfig 保留，靠上面的去重过滤器消除双份头）
          globalcors:
            add-to-simple-url-handler-mapping: true
            cors-configurations:
              '[/**]':
                # ★ 严禁用 "*"：allowCredentials=true 时等于任意站点都能带 Cookie 访问
                allowedOriginPatterns:
                  - http://localhost:3000
                  - http://localhost:5173
                  - http://localhost:5174
                allowCredentials: true
                allowedMethods: "*"
                allowedHeaders: "*"
                maxAge: 3600

          httpclient:
            connect-timeout: 3000
            # 全局响应超时：RAG/向量类接口较慢，与 Nginx proxy_read_timeout 对齐
            response-timeout: 120s
            websocket:
              max-frame-payload-length: 4194304     # 4MB
          # ★★ SC 2025.0.x 起 X-Forwarded-* 默认不再透传/追加；
          #     不配这里，服务侧拿到的客户端 IP 会是网关自己的 IP（影响 @RateLimit 的 IP 维度与访问日志）
          #     值 = 信任的代理 IP 正则（宿主机 Nginx = 127.0.0.1；容器版 Nginx = docker 网段）
          trusted-proxies: "127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|172\\.\\d+\\.\\d+\\.\\d+"

# ===== Sa-Token：★★ 必须与 6 个业务服务逐字一致（token-name / timeout / cookie 三项尤其）=====
sa-token:
  token-name: isegoria
  timeout: 7200
  active-timeout: 1800
  is-concurrent: true
  is-share: true
  token-style: uuid
  cookie:
    path: /
    secure: false          # docker 环境覆盖为 true
    http-only: true
    same-site: Lax

# ===== 网关自身鉴权策略（GatewayAuthProperties 绑定；依据 = 全仓注解 grep 实测，见 §6.3）=====
gateway:
  auth:
    mode: blacklist        # blacklist（推荐）| whitelist
    public-paths:          # 公开/豁免：blacklist 下默认就放行，这份清单是「文档 + 保护组豁免名单」
      # 全局
      - /doc.html
      - /swagger-ui.html
      - /v3/api-docs/**
      - /webjars/**
      - /actuator/**
      - /api/ws              # ★ WebSocket 必须放行（notify 自己握手鉴权）
      - /api/ws/**
      # 登录注册（源码无鉴权注解）
      - /api/user/login
      - /api/user/register
      - /api/user/forgetPassword
      - /api/admin/user/login
      - /api/admin/user/register
      - /api/admin/user/forgetPassword
      - /api/admin/user/otherUserInfo
      # 游客浏览（@SaIgnore 实测清单）
      - /api/post/list       # PostController:155
      - /api/post/*          # GET /post/{id}，PostController:171
      - /api/post/*/view     # PostController:198
      - /api/comment/list    # CommentController:70
      - /api/comment/replies # CommentController:82
      - /api/boards/list     # BoardController:56
      - /api/admin/boards/list   # BoardManagerController:44
      - /api/tag/hot         # ★ TagController:36 —— 只有 /hot，/list 与 /{id} 受类级 @SaCheckLogin 保护
      - /api/admin/tag/hot
      - /api/recommend/feed  # ★ RecController:47 游客要能刷 feed
      - /api/search/post     # SearchController 无注解，游客可搜
    protected-paths:       # ★ 只写「整组都需要登录」的前缀（判据 = 类级 @SaCheckLogin / @SaCheckRole）
      - /api/adminPost/**            # 类级 @SaCheckLogin + @SaCheckRole("admin")
      - /api/admin/audilog/**        # 类级 @SaCheckRole("admin")
      - /api/admin/score-config/**   # 类级 @SaCheckRole("admin")
      - /api/admin/sensitive-words/**# 类级 @SaCheckRole("admin")
      - /api/admin/tag/**            # 类级 @SaCheckLogin（/hot 已豁免）
      - /api/admin/boards/**         # 类级 @SaCheckLogin（/list 已豁免）
      - /api/admin/search/**         # 类级 @SaCheckRole("admin")
      # user 管理端**没有类级注解**，逐组列举
      - /api/admin/user/out
      - /api/admin/user/cancel
      - /api/admin/user/edit
      - /api/admin/user/editPassword
      - /api/admin/user/status       # @SaCheckRole("admin")
      - /api/admin/user/list         # @SaCheckRole("admin")
      - /api/admin/user/getUserInfo  # @SaCheckRole("admin")
      - /api/notifications/**        # 类级 @SaCheckLogin
      - /api/collect/**              # 类级 @SaCheckLogin
      - /api/userFollow/**           # 类级 @SaCheckLogin
      - /api/boardFollow/**          # 类级 @SaCheckLogin
      - /api/postImage/**            # 类级 @SaCheckLogin
      - /api/comment/**              # 类级 @SaCheckLogin（/list、/replies 已豁免）
      - /api/tag/**                  # 类级 @SaCheckLogin（/hot 已豁免）
      - /api/recommend/**            # 类级 @SaCheckLogin（/feed 已豁免）
    # ★★ 刻意不列入的 4 个前缀（防后人顺手加回来）：
    #   /api/post/**     同前缀混公开(list/GET {id}/{id}/view)与私有(add/草稿/分享…)，
    #                    Sa 的 * 也匹配 /api/post/add → 无法只拦一半；服务层有干净的类级 @SaCheckLogin + 3 个 @SaIgnore
    #   /api/user/**     UserController 无类级注解，逐方法差异大（/status、/list、/getUserInfo 是 admin）
    #   /api/boards/**   BoardController 无类级注解（写操作逐个 @SaCheckLogin）
    #   /api/postLike/** LikeController 全无注解，且 /count 是纯公开的游客接口

management:
  endpoints:
    web:
      exposure:
        include: health,info,gateway,prometheus
  endpoint:
    health:
      show-details: never
    gateway:
      access: read_only           # 允许 GET /actuator/gateway/routes，禁止 POST 改路由

logging:
  level:
    root: info
    com.ruwei: info
    org.springframework.cloud.gateway: info
```

> ### ★ 已落地的实际文件（2026-09-18）——与本节的示意有 3 处差异，以文件为准
>
> 骨架已经建好（**未改任何业务服务**）：
>
> | 文件 | 说明 |
> |---|---|
> | `isegoria-gateway/pom.xml` | parent = `spring-boot-starter-parent:4.0.8`（外部 parent，`<relativePath/>` 空值）；已列进根 pom 的 `<modules>`（**只聚合不继承**，见 §4.1） |
> | `isegoria-gateway/src/main/java/com/ruwei/gateway/IsegoriaGatewayApplication.java` | 启动类（`@EnableScheduling` 供 P3 的 Same-Token 刷新用） |
> | `isegoria-gateway/src/main/resources/application.yml` | 本地配置（路由 10 条 + 鉴权清单 + sa-token） |
> | `isegoria-gateway/src/main/resources/application-docker.yml` | 只覆盖 Redis/Nacos/trusted-proxies/CORS/6 个主机 + `cookie.secure` |
>
> **差异 1｜路由 URI 用 `${app.hosts.*}` 间接引用**：yml 中部有 `app.hosts.{user,post,…}: 127.0.0.1`，
> 路由写 `uri: http://${app.hosts.user}:8201`。好处是 Docker 环境只覆盖那 6 行即可，**不必复制整张路由表**（避免双份配置漂移）。
>
> **差异 2｜白名单按注解实测**：本节示意版里曾把 `/api/tag/list`、`/api/admin/tag/{list,adminList,{id}}`
> 当作公开接口 —— **实测是错的**：`TagController` / `TagManagerController` 有**类级 `@SaCheckLogin`**，
> 只有 `/hot` 带 `@SaIgnore`。落地文件已按实测修正，并把"只保护整组需登录的前缀"这条原则写进注释。
>
> **差异 3｜`/api/post/**`、`/api/user/**`、`/api/postLike/**`、`/api/boards/**` 不进保护组**（原因见上方注释块与 §6.3）。
>
> **自检脚本**：`.workbuddy/_check_gateway.py`（python + pyyaml）会校验 pom 的 parent/版本/依赖、
> 父 pom 未纳入网关、yml 用新前缀、10 条路由的 order 与 URI 形态、WS 的 `response-timeout: -1`、
> 保护组豁免齐全、以及 **sa-token 段与 `isegoria-user` 逐字一致**。改配置后重跑一次即可：
> `python .workbuddy/_check_gateway.py`

### 4.5 路由表设计要点（复用 §1.2）

1. **只写到"第二段"的具体前缀**，`/api/admin/*` 这个共享第一段绝不写通配。
2. **显式 `order`**：SCG 按 order 升序匹配，`order` 相同的按声明顺序。写上 order 后，将来加路由不会因为插入位置不同而出现"偶发串服务"。
3. **WS 单独一条 + `response-timeout: -1`**：全局 `response-timeout` 会作用到 WS 代理连接上，表现为"连接稳定 X 分钟后被掐断重连"（X = response-timeout）。
4. **URI 形式与 §2.4 的结论绑定**：现在是静态 URI；确认 Nacos 里实例端口是 HTTP 端口后，可整表换成 `lb://isegoria-user-http` 之类（WS 那条要写 `lb:ws://isegoria-notify-http`）。
5. **不加 `StripPrefix`**：业务服务的 `context-path: /api` 要求 `/api` 原样保留（与 Nginx 的 `proxy_pass` 不带 URI 是同一个道理）。

### 4.6 本地跑起来

```bash
# 依赖：Redis（6379）+ 6 个业务服务（至少起 user/post）+ Nacos（可选）
cd isegoria-gateway
mvn -B -DskipTests clean package
java -jar target/isegoria-gateway-1.0.0-SNAPSHOT.jar

# ① 路由不为空（★ 验证 §2.1 的前缀）
curl -s 127.0.0.1:8100/actuator/gateway/routes | python -m json.tool | grep '"id"'
# ② 直连网关访问业务（不经过 Nginx）
curl -i 127.0.0.1:8100/api/post/list -X POST -H 'Content-Type: application/json' -d '{"page":1,"pageSize":5}'
# ③ 未命中路由时的返回体（P0 阶段可能还是 Whitelabel，P2 起是 BaseResponse 形状）
curl -i 127.0.0.1:8100/api/not-exist
```

**验证点**：

- [ ] 启动日志里有 `Netty started on port 8100`，且 `WebApplicationType: REACTIVE`
- [ ] `/actuator/gateway/routes` 返回 10 条以上路由（**不是 `[]`**）
- [ ] `curl :8100/api/post/list` 能拿到帖子列表 JSON（说明转发链路通、`/api` 前缀没被吃掉）
- [ ] `curl :8100/api/ws/info` 返回 SockJS 的 `{"websocket":true,...}`（WS 路由通）

---

## 五、P1：纯路由灰度（不鉴权、不限流）

**原则：先把"转发"这一步跑稳，再加任何横切能力。** 一次性把鉴权搬过来，出问题无法定位是路由还是鉴权。

### 5.1 Nginx 改动（宿主机版 `deploy/nginx/isegoria.conf`）

```nginx
# ---------- 上游：6 个服务 upstream 全部换成 1 个网关 upstream ----------
upstream isegoria_gateway { server 127.0.0.1:8100; keepalive 32; }
# 双实例时（推荐）：
# upstream isegoria_gateway {
#     server 127.0.0.1:8100;
#     server 127.0.0.1:8110;      # 第二个实例（不同端口/机器）
#     keepalive 32;
# }

# ---------- API 段：全部交给网关（原来的 6 组 location 整段注释保留，便于回滚） ----------
location /api/ {
    proxy_pass http://isegoria_gateway;      # ★ 依旧不能带 URI（带了会吃掉 /api）
}

# WebSocket：指到网关（不是 notify），Upgrade 透传与超时不变
location /api/ws {
    proxy_pass http://isegoria_gateway;
    proxy_http_version 1.1;
    proxy_set_header Upgrade    $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host       $host;
    proxy_set_header X-Real-IP  $remote_addr;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
    proxy_buffering off;
}

# 运维端点绝不外露（网关自己的 /actuator 只允许本机访问）
location /actuator/ { return 404; }

# ================= 回滚用（原直连配置，整段保留，回滚时把上面的 location /api/ 注释掉） ==========
# location /api/user/      { proxy_pass http://isegoria_user; }
# location /api/admin/user/ { proxy_pass http://isegoria_user; }
# location /api/post/      { proxy_pass http://isegoria_post; }
# …（照抄 phase12 §7 的原表）
```

> 容器版 `deploy/nginx/isegoria.docker.conf` 同理，把 upstream 改成 `server app-gateway:8100;`，`depends_on` 加 `app-gateway`。

### 5.2 Nginx 生效与验证

```bash
sudo nginx -t && sudo nginx -s reload
curl -i https://www.example.com/api/post/list -X POST -H 'Content-Type: application/json' -d '{"page":1,"pageSize":5}'
# 期望：200 + 业务 JSON；同时网关日志里出现这条转发的访问日志
```

### 5.3 回滚（≤1 分钟）

```bash
# 回滚 = 把 location /api/ 注释掉 + 恢复原 6 组 location（已在配置里注释保留）
sudo nginx -t && sudo nginx -s reload
```

**P1 回归清单**（每阶段都跑一遍，重点关注这 4 类）：

- [ ] 登录 / 注册 / 退出（Cookie `isegoria` 能正常下发与回传）
- [ ] 发帖 → 送审 → 列表可见；图片上传（大 body，multipart 透传）
- [ ] 游客链路：帖子列表 / 详情 / 评论 / 板块 / 搜索 / 推荐流
- [ ] `Long → String`：响应里 `id` 仍是字符串（网关不该动 body，验一下没被网关解析破坏）
- [ ] WebSocket 稳定连接 > 30 分钟不断（验证 `response-timeout: -1` 与 Nginx 超时）
- [ ] 管理端：登录 + 用户列表 + 审核 + `reindex`

---

## 六、P2：Sa-Token 网关统一鉴权

### 6.1 原理（为什么后端几乎零改造）

```
user 服务                     网关                        业务服务
① 校验账号密码
② StpUtil.login() 写会话
   └→ 共享 Redis（key = token）
                             ③ 拿 cookie/header 里的 token 去 Redis 查
                                ├ 查到 → 放行并**原样透传 token**
                                └ 查不到 → 返回 {code:40100}
                                                        ④ 仍能 StpUtil.getLoginIdAsLong()
                                                           （token 透传 + 同一个 Redis）
```

**关键**：会话在共享 Redis，网关只做"查 + 拦 + 放行"；**token 继续透传**，所以业务服务里遍布的 `StpUtil.getLoginIdAsLong()` / `@SaCheckLogin` 一行都不用改。

### 6.2 `SaTokenGatewayConfig`（照抄）

`isegoria-gateway/src/main/java/com/ruwei/gateway/config/GatewayAuthProperties.java`

```java
package com.ruwei.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权策略配置（白名单 / 保护组 / 模式）。
 *
 * <p>两种模式的失败模式完全不同，务必理解后再选（见手册 §6.3）：</p>
 * <ul>
 *   <li>{@code blacklist}（推荐）：默认**不校验**，只对 {@link #protectedPaths} 里明确列出的
 *       "整组需要登录"的路径做粗筛。漏配的后果 = 该接口少了网关这层粗筛，
 *       但业务服务内的 {@code @SaCheckLogin} 仍会拦住 → <b>不会造成线上功能不可用</b>。</li>
 *   <li>{@code whitelist}：默认**全拦**，只放行 {@link #publicPaths}。漏配的后果 =
 *       游客接口被 401，<b>游客功能整体不可用</b>。</li>
 * </ul>
 *
 * @author ruwei
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "gateway.auth")
public class GatewayAuthProperties {

    /** 策略模式：blacklist（默认，推荐）| whitelist */
    private String mode = "blacklist";

    /** 游客可访问 / 无需网关校验的路径（Sa 通配：* 单层，** 多层） */
    private List<String> publicPaths = new ArrayList<>();

    /** 明确需要登录的路径（blacklist 模式使用；保护组优先于公开组） */
    private List<String> protectedPaths = new ArrayList<>();
}
```

`isegoria-gateway/src/main/java/com/ruwei/gateway/config/SaTokenGatewayConfig.java`

```java
package com.ruwei.gateway.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.ruwei.gateway.core.GatewayResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关统一鉴权配置（Sa-Token Reactor 版）。
 *
 * <p>只做"是否登录"的粗筛：角色/权限细判留在业务服务（网关不该懂业务权限）。
 * 业务服务内的 {@code @SaCheckLogin} / {@code @SaCheckRole} <b>保留不删</b>——纵深防御，
 * 万一网关漏了某条路由、或有人绕过网关直连，服务内还能兜住。</p>
 *
 * <p>响应契约：<b>必须与业务服务一致</b> —— HTTP 200 + {@code {"code":40100,"data":null,"message":"未登录"}}。
 * 若网关自己返回 HTTP 401 + 别的 body 形状，前端按 {@code code} 判断登录态的拦截器会漏判，
 * 表现为"登录过期后页面不跳登录页、而是显示各种诡异错误"。</p>
 *
 * @author ruwei
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SaTokenGatewayConfig {

    private final GatewayAuthProperties authProperties;

    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/**")
                .setAuth(obj -> {
                    if (isWhitelistMode()) {
                        // ---- 模式二：窄白名单（phase9 写法）。默认全拦，只放行 public-paths ----
                        // 注意失败模式：漏配任何游客接口 = 该功能 401
                        log.debug("网关鉴权（whitelist）path={}", SaHolder.getRequest().getRequestPath());
                        SaRouter.match("/**").check(r -> StpUtil.checkLogin());
                        return;
                    }
                    // ---- 模式一（推荐）：只对"明确需要登录的整组路径"做粗筛 ----
                    // notMatch(publicPaths)：公开接口显式豁免（保护组优先于公开组）
                    SaRouter.match(authProperties.getProtectedPaths())
                            .notMatch(authProperties.getPublicPaths())
                            .check(r -> StpUtil.checkLogin());
                })
                .setError(e -> {
                    // ★ 契约：与业务服务 GlobalExceptionHandler 一致（HTTP 200 + code 语义）
                    if (e instanceof NotLoginException) {
                        log.info("网关拦截未登录请求 path={} reason={}",
                                SaHolder.getRequest().getRequestPath(), e.getMessage());
                        return GatewayResult.notLogin();
                    }
                    if (e instanceof NotRoleException || e instanceof NotPermissionException) {
                        log.info("网关拦截无权限请求 path={}", SaHolder.getRequest().getRequestPath());
                        return GatewayResult.noAuth();
                    }
                    log.error("网关鉴权异常 path={}", SaHolder.getRequest().getRequestPath(), e);
                    return GatewayResult.error("系统错误，请联系管理员");
                });
    }

    private boolean isWhitelistMode() {
        return "whitelist".equalsIgnoreCase(authProperties.getMode());
    }
}
```

`isegoria-gateway/src/main/java/com/ruwei/gateway/core/GatewayResult.java`

```java
package com.ruwei.gateway.core;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关统一响应体。
 *
 * <p><b>字段必须与业务服务的 {@code com.ruwei.common.core.BaseResponse} 逐字一致</b>
 * （code / data / message）——这是前端唯一的错误判断依据，网关不能自创契约。</p>
 *
 * <p>错误码取业务服务的同一套（见 {@code ErrorCode}）：40100 未登录 / 40101 无权限 /
 * 40400 路由不存在 / 42900 限流 / 50000 系统异常。</p>
 *
 * @author ruwei
 */
@Data
public class GatewayResult implements Serializable {

    /** 未登录 */
    public static final int CODE_NOT_LOGIN = 40100;
    /** 无权限 */
    public static final int CODE_NO_AUTH = 40101;
    /** 路由/资源不存在 */
    public static final int CODE_NOT_FOUND = 40400;
    /** 限流 */
    public static final int CODE_RATE_LIMIT = 42900;
    /** 系统异常 */
    public static final int CODE_SYSTEM = 50000;

    private int code;
    private Object data;
    private String message;

    public GatewayResult(int code, Object data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public static GatewayResult notLogin() {
        return new GatewayResult(CODE_NOT_LOGIN, null, "未登录");
    }

    public static GatewayResult noAuth() {
        return new GatewayResult(CODE_NO_AUTH, null, "无权限");
    }

    public static GatewayResult notFound() {
        return new GatewayResult(CODE_NOT_FOUND, null, "接口不存在");
    }

    public static GatewayResult rateLimited() {
        return new GatewayResult(CODE_RATE_LIMIT, null, "操作过于频繁，请稍后再试");
    }

    public static GatewayResult error(String message) {
        return new GatewayResult(CODE_SYSTEM, null, message);
    }

    /** 便于在配置文件/日志里打印（不含 data） */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        return map;
    }
}
```

### 6.3 ★ 白名单：为什么本手册推荐 "blacklist 模式"

phase9 §7.3 要求"用 grep 扫一遍把所有免登录接口列进白名单"。这里有个**结构性障碍**必须先说清：

**本项目的鉴权粒度是"方法级注解"，不是"路径级"** —— `SaTokenConfigure` 只注册了 `SaInterceptor`，
**没有注解的接口就是公开接口**。而 `PostController` 把公开与私有混在同一前缀下：

| 路径 | 方法 | 是否需登录 |
|---|---|---|
| `/api/post/list` | POST | ❌ 公开（`@SaIgnore`） |
| `/api/post/{id}` | GET | ❌ 公开（`@SaIgnore`） |
| `/api/post/add` | POST | ✅ 需要（无注解 + 服务内有 `@SaCheckLogin`） |
| `/api/post/{id}/view` | POST | ❌ 公开（`@SaIgnore`） |

Sa-Token 的 `*` 是**路径通配，不看 HTTP 方法**，所以"窄白名单"没法精确表达"`/api/post/{id}` 公开、
`/api/post/add` 需要登录"（写 `/api/post/*` 会把 add 也放行 → 依赖服务内兜底；写具体列表则漏一个就是 401）。

| | blacklist（推荐） | whitelist（phase9 写法） |
|---|---|---|
| 语义 | 默认放行，**只对明确需要登录的整组路径**要求登录 | 默认拦截，**只放行列举的公开接口** |
| 漏配的后果 | 该接口少了网关粗筛 → **服务内注解仍会拦住**（无用户可见影响，只是少了一分防护） | 游客接口返回 401 → **游客功能整体不可用（线上事故）** |
| 需要维护的清单 | "受保护"清单（**新增受保护接口时漏写，安全性由服务内注解保证**） | "公开"清单（**新增公开接口时漏写 = 功能挂**） |
| 适合 | **鉴权权威在服务内、网关只做粗筛**的本项目 | 严格边界、服务内不保留注解的场景 |

**★ 因此本手册默认 `mode: blacklist`**，并在 §6.5 明确要求"服务内注解保留"。若你要严格模式，改 `gateway.auth.mode: whitelist` 即可（`public-paths` 那份清单**必须**逐条 grep 复核）。

**不论哪种模式，`public-paths` 都必须包含的 4 类**（漏了必出事）：

| 类型 | 路径 | 依据 |
|---|---|---|
| 登录注册 | `/api/user/login`、`/api/user/register`、`/api/admin/user/login` | 登录本身不能要登录态 |
| 游客浏览 | `/api/post/list`、`/api/post/*`、`/api/post/*/view`、`/api/comment/list`、`/api/comment/replies`、`/api/boards/list`、`/api/admin/boards/list`、`/api/tag/hot`、`/api/admin/tag/hot`、`/api/search/post` | `@SaIgnore`（PostController:155/171/198、CommentController:70/82、BoardController:56、TagController:36、BoardManagerController:44、TagManagerController:36、RecController:47） |
| **推荐流** | `/api/recommend/feed` | ⚠️ phase8 特意写的"别顺手加 `@SaCheckLogin`"，游客要能刷 feed（`RecController:47` 是 `@SaIgnore`） |
| **WebSocket** | `/api/ws`、`/api/ws/**` | ⚠️ **必须放行**（§8.4）：notify 的 `AuthHandshakeInterceptor` 自己会校验 Cookie，网关再拦会导致 SockJS 握手 401、前端永远连不上 |

**★ 两条实测修正（本轮 grep 全仓注解后得到的，与原先凭印象写的不一样）**：

**(1) 保护组只写"整组需登录"的前缀，`/api/post/**`、`/api/user/**`、`/api/postLike/**`、`/api/boards/**` 刻意不列入。**

| 前缀 | 为什么不能进保护组 |
|---|---|
| `/api/post/**` | 前缀下混着公开（`list` / `GET {id}` / `{id}/view`）与私有（`add` / `update` / 草稿 / 分享 …），而 Sa 的 `*` 也匹配 `/api/post/add` → **无法只拦一半**；服务层本来就有干净的类级 `@SaCheckLogin` + 3 个 `@SaIgnore`，网关这层交给它即可 |
| `/api/user/**` | `UserController` **无类级注解**，逐方法差异大（`/status`、`/list`、`/getUserInfo` 是 `@SaCheckRole("admin")`） |
| `/api/boards/**` | `BoardController` 无类级注解（写操作逐个 `@SaCheckLogin`） |
| `/api/postLike/**` | `LikeController` **全无注解**，且 `GET /post/{code}/count` 是纯公开的游客接口（帖子详情页要显示点赞数）——**误列会直接把游客的点赞数打掉** |

**(2) `TagController` / `TagManagerController` 的类注释与代码不一致**（既有问题，需要你定产品口径）：

```java
/** <p>查询接口公开（无需登录）；增删改属平台管理行为，标注 @SaCheckRole("admin")…</p> */  // 类注释这么说
@RestController
@RequestMapping("/tag")
@SaCheckLogin          // ← 但实际是类级"需要登录"
public class TagController {
    @GetMapping("/hot")   @SaIgnore   // 只有这一个真的公开
    @GetMapping("/list")              // ← 实际需要登录（类级生效）
    @GetMapping("/{id}")              // ← 实际需要登录
```

后果：**游客调 `GET /api/tag/list`、`GET /api/tag/{id}` 会拿到 `40100`**。如果前端首页在未登录时确实调它，那是既有 bug（与网关无关）；若产品口径就是"标签列表要登录"，请把类注释改对，并把 `/api/tag/list` 从任何"公开清单"里删掉（落地文件已按代码为准）。

**另一处同类问题**：`GET /api/user/userInfo` **没有** `@SaCheckLogin`（旁边注释却写着"@SaCheckLogin 已保证登录态"），它靠内部 `StpUtil.getLoginIdAsLong()` 抛 `NotLoginException` 兜住 → 游客会拿到 40100，行为上没错，但注解是漏的，建议补上（`UserManagerController` 的 `/admin/user/userInfo` 同样）。

**核对方法（grep 生成权威清单）**：

```bash
# ① 找出所有"需要登录/需要角色"的注解（这些是保护组候选；注意**类级**注解同样会被 grep 到）
rg -n -B2 '@(SaCheckLogin|SaCheckRole|SaCheckPermission)' isegoria-*/src/main/java --glob '*Controller.java'

# ② 找出所有显式公开的方法
rg -n -B2 '@SaIgnore' isegoria-*/src/main/java --glob '*Controller.java'

# ③ 再按类筛一遍：既没 ① 也没 ② 的方法，就是"无注解 = 公开"——按 §6.3 的判断，
#    它们在 blacklist 模式下**不需要**进白名单（网关默认放行），在 whitelist 模式下**必须**进白名单
```

> 落地文件的 `application.yml` 注释里已把"哪些前缀进保护组、哪些刻意不进、为什么"逐条写清，
> 并附上每个结论对应的源码行号 —— 改动前先看注释，别只改键值。

### 6.4 路由未命中 / 后端不可用时的返回体

默认情况下，未命中路由会返回 Spring Boot 的白页（HTML），与业务契约不一致。加一个错误处理器统一：

`isegoria-gateway/src/main/java/com/ruwei/gateway/config/GatewayErrorConfig.java`

```java
package com.ruwei.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruwei.gateway.core.GatewayResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.error.ErrorAttributeOptions;
// ★ Boot 4 把这两个类从 boot 3 的 org.springframework.boot.web.reactive.error 挪到了 webflux.error
//   （写老路径 = 编译期就报 "程序包不存在"，别照抄网上 Boot 3 的文章）
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关错误响应统一化：把"路由未命中 / 后端不可用"改成与业务服务一致的 BaseResponse 形状。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>路由未命中 → HTTP 200 + code 40400（与后端"业务错误也返回 200"的契约一致）；</li>
 *   <li>后端实例不可用（NotFoundException / 连接失败）→ HTTP 503 + code 50000
 *       （保留非 200 状态码，便于 Nginx/云监控识别为故障）。</li>
 * </ul>
 *
 * @author ruwei
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class GatewayErrorConfig {

    private final ObjectMapper objectMapper;

    @Bean
    @Order(-1)   // 必须早于 Spring 默认的 DefaultErrorWebExceptionHandler
    public ErrorWebExceptionHandler gatewayErrorWebExceptionHandler(ErrorAttributes errorAttributes) {
        return (exchange, ex) -> {
            ServerHttpResponse response = exchange.getResponse();
            if (response.isCommitted()) {
                return Mono.error(ex);
            }
            GatewayResult body;
            HttpStatus status;
            if (isBackendDown(ex)) {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                body = GatewayResult.error("服务暂时不可用，请稍后再试");
                log.error("后端实例不可用 path={} err={}",
                        exchange.getRequest().getPath(), ex.getMessage());
            } else if (ex instanceof ResponseStatusException rse
                    && rse.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                status = HttpStatus.OK;
                body = GatewayResult.notFound();
                log.warn("网关未匹配到路由 path={}", exchange.getRequest().getPath());
            } else {
                status = HttpStatus.OK;
                body = GatewayResult.error("系统错误，请联系管理员");
                log.error("网关异常 path={}", exchange.getRequest().getPath(), ex);
            }
            response.setStatusCode(status);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        };
    }

    private boolean isBackendDown(Throwable ex) {
        return ex instanceof NotFoundException
                || ex instanceof java.net.ConnectException
                || ex.getCause() instanceof java.net.ConnectException;
    }
}
```

> `ErrorAttributeOptions` 那条 import 是为了兼容不同 Boot 小版本的签名差异；若 IDEA 报未使用，直接删掉即可。
> `NotFoundException` 在 SCG 5.x 的包路径是 `org.springframework.cloud.gateway.support.NotFoundException`；若 IDE 找不到，用 `Ctrl+Shift+F` 在 jar 里搜 `NotFoundException` 确认包名（5.0 有过一次内部包重整）。

### 6.5 服务内的 Sa-Token 校验**保留**（不要删）

| 保留的理由 | 说明 |
|---|---|
| 纵深防御 | 网关漏配某路由 / 有人绕过网关直连（Same-Token 上线前）→ 服务内还能兜住 |
| 权限细判 | `@SaCheckRole("admin")`、`@SaCheckPermission` 本来就必须留在服务内（网关不该懂业务权限） |
| 成本 | 一次 Redis 读，可忽略（与网关读的是同一份会话） |

**网关做粗筛（挡住大部分未登录流量） + 服务内做细判** —— 分工，不是替代。

### 6.6 P2 验证

```bash
# ① 未登录访问受保护接口：网关 40100，且业务服务日志里**没有**这条请求（证明拦在网关）
curl -i https://www.example.com/api/post/add -X POST -H 'Content-Type: application/json' -d '{}'

# ② 登录后同一个请求：网关放行，服务内正常取到 loginId
curl -i -c cookie.txt https://www.example.com/api/user/login -X POST \
     -H 'Content-Type: application/json' -d '{"username":"xxx","password":"xxx"}'
curl -i -b cookie.txt https://www.example.com/api/user/userInfo
```

- [ ] ① 返回 `{"code":40100,"data":null,"message":"未登录"}`，HTTP 200
- [ ] ② 登录 → `/api/user/userInfo` 正常返回（证明会话透传成功）
- [ ] 已登录请求，**后端日志里能看到该请求**（证明放行的是真流量）
- [ ] 游客 6 条链路全通（帖子列表/详情/评论/板块/搜索/推荐流）
- [ ] `/api/ws/**` 仍能握手（白名单生效）

---

## 七、P3：Same-Token 防绕过

**问题**：网关拦住了外网，但如果内网还能直连子服务（同网段其他机器、误暴露的端口、将来 K8s Pod 互访），就能**绕过网关鉴权**。

**现状判断**：当前端口只绑 127.0.0.1 且不进安全组，直连风险低 —— 所以这一阶段**可以放在 P2 稳定后单独一批做**；但**只要将来多机部署或上 K8s，就必须有**。

### 7.1 网关侧：注入 `SA-SAME-TOKEN`

`isegoria-gateway/src/main/java/com/ruwei/gateway/filter/SameTokenForwardFilter.java`

```java
package com.ruwei.gateway.filter;

import cn.dev33.satoken.same.SaSameUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Same-Token 注入过滤器：给每个转发到业务服务的请求加上 {@code SA-SAME-TOKEN} 头。
 *
 * <p>与业务服务侧的校验过滤器（见 §7.2）配合，构成"只有经过网关的请求才能进服务"的边界。</p>
 *
 * <p>注意：本过滤器只加不删，且<b>不修改 Cookie</b> —— notify 的 WS 握手靠 {@code isegoria}
 * 这个 Cookie 取 token，任何对 Cookie 的改写都会让 WS 认证失败。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class SameTokenForwardFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String sameToken;
        try {
            sameToken = SaSameUtil.getToken();
        } catch (Exception e) {
            // Same-Token 取不到时**不放行**：宁可 503 也不能放一个没有边界标记的请求进去
            log.error("Same-Token 获取失败，拒绝转发 path={}", exchange.getRequest().getPath(), e);
            return Mono.error(e);
        }
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(SaSameUtil.SAME_TOKEN, sameToken)
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    /** 尽量早：必须早于路由转发（NettyRoutingFilter 的 order 是最大整数） */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
```

### 7.2 定时刷新（★ 不要依赖默认自刷新）

官方明确：Same-Token 默认自刷新（过期后重新生成）**在高并发下会造成毫秒级服务失效**，只适合开发/低并发；
且**集群里不要多个服务重复调用刷新**。所以：**在网关侧单点刷新 + Redis 分布式锁**。

`isegoria-gateway/src/main/java/com/ruwei/gateway/task/SameTokenRefreshJob.java`

```java
package com.ruwei.gateway.task;

import cn.dev33.satoken.same.SaSameUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Same-Token 主动刷新任务。
 *
 * <p>为什么需要它：Same-Token 默认有效期 1 天、带"自刷新"机制，但官方说明自刷新在高并发下
 * 会造成毫秒级服务失效；生产应<b>专门用一个定时任务主动刷新</b>，且刷新间隔要远小于有效期。</p>
 *
 * <p>多实例安全：网关若部署 2 个实例，两个实例都会跑本任务 → 用 Redis SETNX 竞争锁，
 * 保证同一时刻集群内只有一次刷新（Sa-Token 刷新时旧 Token 会作为"次级 Token"保留到下次刷新，
 * 因此刷新瞬间不会有请求被拒）。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SameTokenRefreshJob {

    /** 全局刷新锁：TTL 比刷新间隔略短，保证锁不会长期占住 */
    private static final String LOCK_KEY = "lock:same-token:refresh";
    private static final Duration LOCK_TTL = Duration.ofMinutes(4);

    private final StringRedisTemplate stringRedisTemplate;

    /** 每 5 分钟一次（cron 六段式：秒 分 时 日 月 周） */
    @Scheduled(cron = "0 */5 * * * *")
    public void refresh() {
        try {
            Boolean got = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL);
            if (!Boolean.TRUE.equals(got)) {
                return;      // 其它实例正在刷 / 刚刷过
            }
            SaSameUtil.refreshToken();
            log.info("Same-Token 已刷新");
        } catch (Exception e) {
            log.error("Same-Token 刷新失败（下个周期会重试）", e);
        }
    }
}
```

> Same-Token 的有效期配置项是 `sa-token.same-token-timeout`（默认 86400 秒）——保持默认即可，**不要去调大**。

### 7.3 业务服务侧：校验 `SA-SAME-TOKEN`（放 `common`，一次搞定）—— ✅ **已落地（2026-09-18）**

`isegoria-common/src/main/java/com/ruwei/common/web/SameTokenCheckFilter.java`

```java
package com.ruwei.common.web;

import cn.dev33.satoken.same.SaSameUtil;
import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ResultUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 内网边界校验：只接受携带合法 Same-Token 的请求（即"必须经过网关"）。
 *
 * <p><b>默认关闭</b>（{@code gateway.same-token.enabled=false}），网关 P3 阶段上线时，
 * 在 6 个服务的配置里（或 compose 的统一环境变量里）打开。</p>
 *
 * <p>放行清单（否则运维与本地联调会很难受）：</p>
 * <ul>
 *   <li>{@code /actuator/**}：存活探测（Nginx/云监控/容器 healthcheck）；</li>
 *   <li>Knife4j / Swagger 文档：{@code /doc.html}、{@code /swagger-ui.html}、{@code /v3/api-docs/**}、{@code /webjars/**}；</li>
 *   <li>本地联调：{@code gateway.same-token.allow-localhost=true} 时放行来自回环地址的请求。</li>
 * </ul>
 *
 * <p>⚠️ Dubbo 内部调用走 tri 协议，不经过本过滤器，不受影响。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gateway.same-token.enabled", havingValue = "true")
public class SameTokenCheckFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${gateway.same-token.allow-localhost:false}")
    private boolean allowLocalhost;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (isExcluded(uri) || (allowLocalhost && isLoopback(request.getRemoteAddr()))) {
            filterChain.doFilter(request, response);
            return;
        }
        String sameToken = request.getHeader(SaSameUtil.SAME_TOKEN);
        if (!StringUtils.hasText(sameToken)) {
            reject(response, "无效 Same-Token：请通过网关访问");
            return;
        }
        try {
            SaSameUtil.checkToken(sameToken);
        } catch (Exception e) {
            log.warn("Same-Token 校验失败 uri={} ip={}", uri, request.getRemoteAddr());
            reject(response, "无效 Same-Token：请通过网关访问");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isExcluded(String uri) {
        return uri.startsWith("/actuator")
                || uri.startsWith("/doc.html")
                || uri.startsWith("/swagger-ui.html")
                || uri.startsWith("/swagger-resources")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/v2/api-docs")
                || uri.startsWith("/webjars")
                || uri.startsWith("/favicon.ico");
    }

    private boolean isLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
    }

    /** 契约同全局异常处理：HTTP 200 + BaseResponse(code=40100)，避免前端误判为网络故障 */
    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        BaseResponse<?> body = ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
```

**开启方式**（compose 里一处生效，6 个服务共用 `x-app-env` 锚点）：

```yaml
# deploy/docker-compose.yml → x-app-env
  GATEWAY_SAME_TOKEN_ENABLED: "true"
```

```yaml
# 各服务 application-docker.yml（或直接依赖 Spring 的 relaxed binding，环境变量已能映射）
gateway:
  same-token:
    enabled: true
    allow-localhost: false
```

> ⚠️ 打开后**立即**会拦掉的东西：直连 8201~8206 的 curl / Postman、Nginx 若还留着旧的直连 location、宿主机上的健康检查脚本（若探的是 8201/api/xxx 而不是 /actuator）。**上线顺序**：先 Nginx 全部走网关 → 再打开这个开关。
>
> **已落地的两点细节**：① 开关写在 6 个服务的 `application.yml`（`gateway.same-token.enabled: false`）
> 与 compose 的 `GATEWAY_SAME_TOKEN_ENABLED`（**环境变量优先级更高**，一次覆盖 6 个服务）；
> ② 过滤器**放行 `OPTIONS` 请求**（CORS 预检不带 Cookie/业务数据，拦它会让前端跨域整体挂掉），
> 另外 `/actuator/**` 与 Knife4j/Swagger 路径也放行。
> 需要本机直连调试又要开着它时：给该服务加 `gateway.same-token.allow-localhost: true`（放行回环地址）。

### 7.4 P3 验证

```bash
# ① 经网关：正常
curl -i -b cookie.txt https://www.example.com/api/user/userInfo

# ② 绕过网关直连服务：必须被拒（返回 code 40100 + "无效 Same-Token"）
curl -i 127.0.0.1:8201/api/user/userInfo -b cookie.txt
```

- [ ] ② 被拒，且服务日志里有 `Same-Token 校验失败`
- [ ] `/actuator/health`（直连）仍可访问（健康检查没被拦）
- [ ] 本地 IDE 直连调试时把 `allow-localhost: true` 打开即可正常工作
- [ ] WS 握手仍正常（握手请求经网关，带着 Same-Token 进来）

---

## 八、P4：限流 / CORS 收口 / 可观测 / WS / 大 body

### 8.1 限流（Redis 令牌桶）

**先说清楚现状**：`@RateLimit` 目前因切点写错而**完全没生效**（§1.4）。所以网关这层不要删、服务内那层修好后也要**保留**（防绕过、且服务内是"业务语义限流"，网关是"流量保护限流"，两者目的不同）。

`isegoria-gateway/src/main/java/com/ruwei/gateway/config/RateLimitConfig.java`

```java
package com.ruwei.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpCookie;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 限流 Key 解析器（配合 RequestRateLimiter 过滤器使用，见 application.yml 的路由 filters）。
 *
 * <p>按 IP 限流是兜底（挡爬虫/刷接口）；按用户限流更精确（读 Cookie 里的 token 作为身份指纹，
 * <b>不需要解析会话</b>——把 token 原文哈希即可，避免网关与业务服务的会话格式耦合）。</p>
 *
 * @author ruwei
 */
@Slf4j
@Configuration
public class RateLimitConfig {

    /** Cookie 名与 Sa-Token 的 token-name 一致 */
    private static final String COOKIE_NAME = "isegoria";
    private static final String HEADER_NAME = "isegoria";

    /**
     * 按客户端 IP：优先 X-Forwarded-For 首值（Nginx 加的），回退 remoteAddress。
     *
     * <p>⚠️ 前提是网关的 {@code spring.cloud.gateway.server.webflux.trusted-proxies} 配了 Nginx 的 IP，
     * 否则拿到的 remoteAddress 是网关自己。</p>
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (StringUtils.hasText(xff)) {
                return Mono.just(xff.split(",")[0].trim());
            }
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            return Mono.just(remote == null ? "unknown" : remote.getAddress().getHostAddress());
        };
    }

    /** 按登录身份：token 原文取哈希前 16 位（无法反推，且不依赖 Sa-Token 会话格式） */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String token = exchange.getRequest().getHeaders().getFirst(HEADER_NAME);
            if (!StringUtils.hasText(token)) {
                HttpCookie cookie = exchange.getRequest().getCookies().getFirst(COOKIE_NAME);
                if (cookie != null) {
                    token = cookie.getValue();
                }
            }
            if (StringUtils.hasText(token)) {
                return Mono.just("u:" + Integer.toHexString(token.hashCode()));
            }
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            return Mono.just("ip:" + (remote == null ? "unknown" : remote.getAddress().getHostAddress()));
        };
    }
}
```

在路由上加限流（**已落地**，形态与你的 `uri` 写法保持一致 —— 用 `${app.hosts.*}` 而非字面 IP）：

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            # admin 与 front 是两条独立路由，所以各挂一份（等效于"一条路由含两个 predicate"）
            - id: user-admin
              order: 10
              uri: http://${app.hosts.user}:8201
              predicates:
                - Path=/api/admin/user/**
              filters:
                - name: RequestRateLimiter
                  args:
                    redis-rate-limiter.replenishRate: 20
                    redis-rate-limiter.burstCapacity: 40
                    redis-rate-limiter.requestedTokens: 1
                    key-resolver: "#{@userKeyResolver}"
            - id: user
              order: 20
              uri: http://${app.hosts.user}:8201
              predicates:
                - Path=/api/user/**
              filters:
                - name: RequestRateLimiter
                  args:
                    # 令牌桶：稳态每秒补 N 个，桶容量 M（允许突发）
                    redis-rate-limiter.replenishRate: 20
                    redis-rate-limiter.burstCapacity: 40
                    redis-rate-limiter.requestedTokens: 1
                    key-resolver: "#{@userKeyResolver}"
```

**★ 三条实测结论（直接拉 SCG 5.0.3 的 jar 逐字节核对，不是抄文档）**：

| 结论 | 依据 |
|---|---|
| 过滤器名就是 `RequestRateLimiter` | jar 里存在 `…/filter/factory/RequestRateLimiterGatewayFilterFactory.class`（name = 类名去掉 `GatewayFilterFactory`） |
| 参数名是 **camelCase** 的 `replenishRate` / `burstCapacity` / `requestedTokens` | `RedisRateLimiter$Config.class` 里这三个字段名逐字存在（前缀常量 `redis-rate-limiter` 在 `RedisRateLimiter.class` 里）。★ 写错名字**不会报错**，只会静默不限流 |
| 桶 key **含 routeId**（`request_rate_limiter.{<routeId>.<用户 key>}.tokens`） | 所以 `user` 与 `user-admin` 是两个独立的桶，同一身份的合计额度是 2×20/s |

**两种等价写法（选一种，别混）**：

```yaml
# 写法 A（当前采用）：每条用它限流的路由自己写 key-resolver
- name: RequestRateLimiter
  args:
    key-resolver: "#{@userKeyResolver}"

# 写法 B：全局给默认值，路由里只写 - RequestRateLimiter（少写一坨）
spring:
  cloud:
    gateway:
      server:
        webflux:
          filter:
            request-rate-limiter:
              default-key-resolver: "#{@userKeyResolver}"   # 类型就是 KeyResolver
              # enabled: true                                  # 总开关（默认 true）
              # default-rate-limiter: "#{@redisRateLimiter}"   # 也可换掉默认限流器实现
```
> 写法 B 的键来自 `spring-configuration-metadata.json`（组 `spring.cloud.gateway.server.webflux.filter.request-rate-limiter`）—— 这是 Gateway 5 新增的全局配置能力，用它能让每条路由只留一个 `- RequestRateLimiter` 标签。

**⚠️ `key-resolver` 引用的是 Bean 名，缺 Bean 会"启动即失败"**（SCG 在加载路由定义时就解析 `#{@...}`），
所以「yml 里引用了哪个 Bean」与「源码里有没有这个 Bean」必须成对维护 ——
本项目已有 `RateLimitConfig` 提供 `ipKeyResolver` / `userKeyResolver` 两个 Bean（Bean 名 = 方法名）。

**⚠️ 按 IP 的那条依赖客户端 IP 真实可读**：`trusted-proxies` 没配 Nginx 地址时，所有请求的 remoteAddress 都是网关自己 → 全体挤进同一个桶，表现为"偶发全站 429"（见 §4.4）。

> 更细的"接口级"限流不必都写在这（会很长）：**登录/注册/忘记密码这类最该限流的接口，服务内的 `@RateLimit` 更合适**（它天然知道接口语义与账号维度）。网关这层按"服务组 + 用户/IP"限一把即可 —— 这就是 phase9 §9 说的"P4 把服务内限流**复制**到网关，不要立刻删服务内的"。

**验证**：

```bash
# 快速压一下，看是否出现 42900
for i in $(seq 1 60); do curl -s -o /dev/null -w "%{http_code} " https://www.example.com/api/user/userInfo; done; echo
curl -s https://www.example.com/api/user/userInfo   # 超限时应返回 {"code":42900,...}
```

### 8.2 CORS 收口（★ 必须去重，否则浏览器直接拒绝）

**问题**：网关 `globalcors` 写一次 `Access-Control-Allow-Origin`，业务服务的 `CorsConfig` 再写一次 →
响应头里出现**两个**同名字段 → 浏览器报 `The 'Access-Control-Allow-Origin' header contains multiple values`。

**做法（本手册选定的方案）**：

1. 网关按 §4.4 配 `globalcors`（用**显式域名**，不用 `*`——`allowCredentials: true` 下 `*` 等于任意站点可带 Cookie）；
2. 网关 `default-filters` 加去重（已在 §4.4 里）：

```yaml
default-filters:
  - DedupeResponseHeader=Access-Control-Allow-Credentials Access-Control-Allow-Origin, RETAIN_FIRST
```

3. **业务服务的 `CorsConfig` 保留不动**：它既是"绕过网关直连"时的兜底，也是"只在一层处理"的另一种可能（将来若把网关的 globalcors 关掉，服务侧仍能跨域）。去重过滤器保证只留一份。

**验证**：

```bash
curl -i -X OPTIONS https://www.example.com/api/user/login \
  -H 'Origin: https://admin.example.com' \
  -H 'Access-Control-Request-Method: POST' | grep -i 'access-control'
# 期望：Access-Control-Allow-Origin 只出现 1 次
```

### 8.3 traceId / 访问日志 / 指标

`isegoria-gateway/src/main/java/com/ruwei/gateway/filter/TraceIdFilter.java`

```java
package com.ruwei.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * traceId 注入 + 统一访问日志。
 *
 * <p>已有 {@code X-Trace-Id} 就复用（便于把外部链路串起来），否则生成一个 16 位短 ID，
 * 并注入到转发请求头里 —— 业务服务只要在日志格式里带上该头，就能做到
 * "网关 → 服务 → Dubbo → MQ" 全链路可查（业务服务侧的 MDC 接入是另一件事，见 §8.3 末）。</p>
 *
 * @author ruwei
 */
@Slf4j
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        final String finalTraceId = traceId;
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(TRACE_ID_HEADER, finalTraceId)
                .build();
        long start = System.currentTimeMillis();
        return chain.filter(exchange.mutate().request(request).build())
                .then(Mono.fromRunnable(() -> {
                    var status = exchange.getResponse().getStatusCode();
                    log.info("[{}] {} {} -> {} ({} ms)", finalTraceId,
                            exchange.getRequest().getMethod(),
                            exchange.getRequest().getPath(),
                            status == null ? "-" : status.value(),
                            System.currentTimeMillis() - start);
                }));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 150;
    }
}
```

**日志格式**（`isegoria-gateway/src/main/resources/logback-spring.xml`，可选）：把 traceId 放进 pattern 更好查；最省事的是用上面的 `log.info` 直接打。

**指标**：`/actuator/prometheus` 已随 POM 暴露。Prometheus 侧建议至少抓两条告警：

```yaml
# 告：网关 5xx 比例（按 route 维度）
- alert: GatewayHigh5xxRate
  expr: sum(rate(spring_cloud_gateway_requests_seconds_count{outcome="SERVER_ERROR"}[5m]))
        / sum(rate(spring_cloud_gateway_requests_seconds_count[5m])) > 0.01
# 告：后端实例不可用（503 突增）
- alert: GatewayBackendUnavailable
  expr: increase(spring_cloud_gateway_requests_seconds_count{status="503"}[5m]) > 10
```

> 指标名以 SCG 5.x 实际暴露为准：先 `curl -s 127.0.0.1:8100/actuator/prometheus | grep -i gateway | head`，再写告警，别照抄老版本的指标名。

**★ 一个必须知道的性能事实：Sa-Token 在 WebFlux 里是「阻塞」的**

实测 `SaReactorFilter.class` 里**没有任何** `Schedulers` / `subscribeOn` / `publishOn` / `fromCallable`
（我把 5.0.3 对应的 sa-token 1.46.0 的 class 扫过一遍：`boundedElastic`、`reactor/core/scheduler/Schedulers` 全部为 `false`），
也就是说它 `setAuth` 里调用的 `StpUtil.checkLogin()` → `SaTokenDao` 走的是 **Spring Data Redis 同步 API**，
**跑在 Netty 事件循环线程上**。

| 影响 | 说明 |
|---|---|
| 吞吐 | 每个鉴权请求都会让事件循环线程阻塞一个 Redis RTT；高并发下事件循环被占满，吞吐明显下降（这跟"网关应该非阻塞"的直觉相反） |
| 提示 | 压测时会看到 Reactor 的 `blocking call!` 警告（或 `BlockHound` 报错） |

**对策**（当前采用第 1 条）：

1. **自己写的过滤器别跟着阻塞**：本项目 `SameTokenForwardFilter` 已把 `SaSameUtil.getToken()`（同样是阻塞 dao）
   包进 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` —— 只有 Sa-Token 自己的鉴权过滤绕不过去；
2. 本项目 QPS 不高（论坛站），**接受 SaReactorFilter 的这一次阻塞**，代价可忽略；
3. 真要极致吞吐：不用 `SaReactorFilter`，改用自己的 WebFilter + **响应式** Redis
   （`ReactiveStringRedisTemplate` 查 `satoken:login:token:<token>` 是否存在）——但这会耦合 Sa-Token 的
   内部 key 结构，Sa-Token 升级时容易踩坑，**不到必要不做**。

### 8.4 WebSocket（最容易漏的一条）

三个要点，缺一个前端就连不上或频繁掉线：

| 要点 | 做法 | 不做会怎样 |
|---|---|---|
| 白名单放行 | `public-paths` 含 `/api/ws`、`/api/ws/**` | SockJS 的 `/api/ws/info`、`/xhr_streaming` 被 401，**前端永远连不上** |
| 不改 Cookie | 过滤链里只加 `SA-SAME-TOKEN`，**不碰 `Cookie`** | notify 的 `AuthHandshakeInterceptor` 靠 `isegoria` Cookie 取 token，改了 → 握手 401 |
| 超时 | WS 路由 `metadata.response-timeout: -1`；Nginx `proxy_read_timeout 3600s` | 表现为"WS 每隔 N 秒重连一次" |

**为什么 WS 也要走网关**：notify 自己**已经能独立鉴权**（`AuthHandshakeInterceptor` 从 Cookie 解析 Sa-Token），所以不需要网关帮它鉴权；但**必须走网关**，否则 Same-Token 上线后 notify 会拒绝直连过来的握手请求。这是 phase9 §8 与 §7.2 叠在一起时的隐含结论，很容易被忽略。

**验证**：

```bash
# 浏览器 DevTools → Network → WS：连接后保持 > 30 分钟不断
# 或用 curl 看 SockJS info 端点
curl -i -b cookie.txt https://www.example.com/api/ws/info
```

### 8.5 大 body / 上传

| 位置 | 配置 | 说明 |
|---|---|---|
| 网关请求体缓冲 | `spring.codec.max-in-memory-size: 12MB` | 只有用到 `ModifyRequestBody`/`CacheRequestBody` 这类过滤器才会缓冲；纯透传不缓冲。仍建议显式设成与 Nginx 一致的 12m，避免将来加过滤器踩坑 |
| 网关转发超时 | `httpclient.response-timeout: 120s` | 与 Nginx `proxy_*_timeout 120s` 对齐；WS 路由用 `-1` 覆盖 |
| Nginx | `client_max_body_size 12m`（已配） | post 服务 multipart 上限 5MB，留一倍余量 |
| post 服务 | `spring.servlet.multipart.max-file-size: 5MB`（已配） | 不动 |

```yaml
spring:
  codec:
    max-in-memory-size: 12MB
```

**验证**：上传一张接近 5MB 的图片（`POST /api/postImage/upload`）——若返回 413，按"网关上 `max-in-memory-size` → 服务 `max-request-size`"顺序查，**不要**先去调大 Nginx。

---

## 九、P5：Docker / 生产部署集成

### 9.1 网关镜像：两种做法（**已采用 A**）

因为网关已列进父聚合 `<modules>`（§4.1），**两个做法都可行**：

| | 做法 | 命令 | 取舍 |
|---|---|---|---|
| **A（✅ 已采用，compose 已接好）** | 复用现有 `deploy/Dockerfile` | `docker build -f deploy/Dockerfile --build-arg SERVICE=gateway -t isegoria/app-gateway:1.0.0 .`<br>（compose 里已经这么配了） | 零新增文件；编译层缓存与 6 个服务共用。**代价是网关编译不过时 6 个服务的镜像也构建不出来**（同一个 reactor） |
| **B（备选，未采用）** | 专用 `deploy/Dockerfile.gateway` | `docker build -f deploy/Dockerfile.gateway -t isegoria/app-gateway:1.0.0 .` | 构建上下文只含 `isegoria-gateway/`（更小更快），但要再多维护一个 Dockerfile；**不能**用来发布到生产（因为 reactor 里已含网关，构建仍受其影响） |

> 做法 A 成立的前提：`isegoria-gateway` 在父聚合 `<modules>` 里，且 jar 名能对上 `deploy/Dockerfile` 里的拷贝规则
> `COPY --from=builder /build/isegoria-${SERVICE}/target/isegoria-${SERVICE}-${APP_VERSION}.jar`
> —— 实际 jar 是 `isegoria-gateway/target/isegoria-gateway-1.0.0-SNAPSHOT.jar`，`SERVICE=gateway` 恰好对得上 ✓（无需改 Dockerfile）。
> 另注：Boot 4 与 Boot 3.5 共用同一个 JRE 17 基础镜像没问题（Boot 4 最低 Java 17）；
> 网关是 WebFlux/Netty 栈，现有 `JAVA_BASE_OPTS` 里那组 `--add-opens` 对它是多余的但无害。
>
> ⚠️ **"网关进 reactor"的这个副作用要记住**：`mvn package` / `docker compose build` 里任一模块编译失败都会整体失败。
> 若某次要紧急发业务服务而网关正好在改，用 `-pl` 跳过它：
> `mvn -B -DskipTests -pl isegoria-user,isegoria-post,isegoria-interaction,isegoria-social,isegoria-notify,isegoria-rec -am package`

下面是做法 B 的 `Dockerfile.gateway` 全文（**备选，未接入 compose**）：

```dockerfile
# syntax=docker/dockerfile:1
# =============================================================
# isegoria-gateway 专用镜像（可选的"隔离构建"做法，见 §9.1 表）
#
# 特点：只 COPY isegoria-gateway/ → 构建上下文更小，不受其它模块改动影响；
#       代价是多维护一个 Dockerfile，且不共享业务服务的 maven 依赖缓存层。
#
# 构建：docker build -f deploy/Dockerfile.gateway -t isegoria/app-gateway:1.0.0 .
#       （build context 依旧是**仓库根目录**）
# =============================================================

FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY deploy/maven/settings.xml /root/.m2/settings.xml
COPY isegoria-gateway /build/isegoria-gateway
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -f /build/isegoria-gateway/pom.xml -DskipTests clean package

FROM eclipse-temurin:17-jre
ARG APP_VERSION=1.0.0-SNAPSHOT
LABEL org.opencontainers.image.title="isegoria-gateway" \
      org.opencontainers.image.description="IsegoriaForum 业务网关（Spring Cloud Gateway）"

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS=""

# 网关是 WebFlux/Netty 栈，反射所需比业务服务少；保留最小一组以防 Netty/Jackson 在强封装下报错
ENV JAVA_BASE_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED \
--add-opens java.base/java.util=ALL-UNNAMED \
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app \
-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Djava.security.egd=file:/dev/./urandom"

WORKDIR /app
RUN useradd -r -u 10001 -m isegoria
COPY --from=builder /build/isegoria-gateway/target/isegoria-gateway-${APP_VERSION}.jar /app/app.jar
RUN chown -R isegoria:isegoria /app
USER isegoria

ENTRYPOINT ["sh", "-c", "exec java $JAVA_BASE_OPTS $JAVA_OPTS -jar /app/app.jar"]
```

### 9.2 网关的 docker 配置

`isegoria-gateway/src/main/resources/application-docker.yml`

```yaml
# =============================================================
# 网关 Docker 环境配置（SPRING_PROFILES_ACTIVE=docker）
#   ★ 主机名一律用 compose 服务名（redis / nacos / app-user …），不要写 127.0.0.1
# =============================================================

spring:
  data:
    redis:
      host: ${REDIS_HOST:redis}
      port: 6379
      password: ${REDIS_PASSWORD}
      database: 0
      timeout: 3000ms

  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_HOST:nacos}:8848
        username: ${NACOS_USER:nacos}
        password: ${NACOS_PASSWORD}

    gateway:
      server:
        webflux:
          # ★ 容器内路由**不在这里重复**：application.yml 的路由 URI 写的是
          #   http://${app.hosts.xxx}:端口，本文件只覆盖下面的 app.hosts 即可（避免双份配置漂移）
          # ★ 容器化后 Nginx 也在 compose 网络里，trusted-proxies 要信任整个容器网段
          trusted-proxies: "172\\.\\d+\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|127\\.0\\.0\\.1"

          globalcors:
            cors-configurations:
              '[/**]':
                allowedOriginPatterns:
                  - ${SITE_URL:https://www.example.com}
                  - ${ADMIN_URL:https://admin.example.com}
                allowCredentials: true
                allowedMethods: "*"
                allowedHeaders: "*"
                maxAge: 3600

# =============================================================
# ★ 关键：把 6 个主机换成 compose 服务名，application.yml 的整张路由表自动指向容器
#   （也可用环境变量覆盖：APP_HOSTS_USER / APP_HOSTS_POST / …）
# =============================================================
app:
  hosts:
    user: ${APP_HOSTS_USER:app-user}
    post: ${APP_HOSTS_POST:app-post}
    interaction: ${APP_HOSTS_INTERACTION:app-interaction}
    social: ${APP_HOSTS_SOCIAL:app-social}
    notify: ${APP_HOSTS_NOTIFY:app-notify}
    rec: ${APP_HOSTS_REC:app-rec}

sa-token:
  cookie:
    secure: true          # 线上 HTTPS 必须 true，否则浏览器不发送 Cookie

logging:
  level:
    root: info
    com.ruwei: info
```

> **与早先版本的差异**：本节原先是"在 docker 文件里把 10 条路由再抄一遍（URI 写 compose 服务名）"。
> 落地时改成了**主机间接引用**（`app.hosts.*`），docker 只覆盖 6 行 —— 路由只维护一份，
> 不会出现"改了本地忘了改 docker"的漂移。`gateway.auth.*` 清单同理，只在 `application.yml` 里维护一份。
> （`gateway.same-token.enabled` 是**业务服务侧**的开关，网关这边没有这个键。）

### 9.3 `deploy/docker-compose.yml` 新增服务（✅ 已落地）

```yaml
  # ==========================================================
  #  网关（业务网关，新增）：Nginx → 网关 → 6 个业务服务
  #  依赖：仅 Redis（Sa-Token 会话 + 限流）+ Nacos（可选服务发现）
  #       不依赖 MySQL/MQ/ES/PG —— 所以它可以最早启动
  # ==========================================================
  app-gateway:
    <<: *app-common
    build:
      context: ..
      dockerfile: deploy/Dockerfile.gateway
      args: { APP_VERSION: "${APP_VERSION:-1.0.0-SNAPSHOT}" }
    image: isegoria/app-gateway:${APP_VERSION:-1.0.0-SNAPSHOT}
    container_name: iseg-app-gateway
    environment:
      <<: *app-env
      JAVA_OPTS: "-Xms256m -Xmx256m"        # WebFlux 内存占用小，256m 足够
    ports: ["127.0.0.1:8100:8100"]          # ★ 依旧只绑本机，绝不进安全组
    depends_on:
      redis:  { condition: service_healthy }
      nacos:  { condition: service_healthy }
    healthcheck:
      test: ["CMD-SHELL", "timeout 2 bash -c '</dev/tcp/127.0.0.1/8100' || exit 1"]
      interval: 30s
      timeout: 5s
      retries: 5
      start_period: 60s
```

并给 `nginx`（profile `nginx`）的 `depends_on` 加上 `app-gateway: { condition: service_started }`；`x-app-env` 里按需补：

```yaml
  GATEWAY_SAME_TOKEN_ENABLED: ${GATEWAY_SAME_TOKEN_ENABLED:-false}   # P3 阶段改 true
```

### 9.4 Nginx（容器版）要点

```nginx
upstream isegoria_gateway { server app-gateway:8100; keepalive 32; }
location /api/  { proxy_pass http://isegoria_gateway; }
location /api/ws { proxy_pass http://isegoria_gateway; ... Upgrade 透传 + 3600s ... }
location /actuator/ { return 404; }
```

### 9.5 `deploy/scripts/start-all.sh` 改动（✅ 已落地）

```diff
 SERVICES=(
+  "gateway:8100:256m"
   "user:8201:256m"
   "post:8202:384m"
   "interaction:8203:256m"
   "social:8204:256m"
   "notify:8205:256m"
   "rec:8206:640m"
 )

-ORDER=(user post interaction social notify rec)
+# gateway 只依赖 Redis/Nacos（网关不吃 Dubbo），放最前面，让它先就绪
+ORDER=(gateway user post interaction social notify rec)
```

> 脚本里的 jar 路径规则是 `$JAR_DIR/isegoria-$svc-1.0.0-SNAPSHOT.jar` → 正好匹配 `isegoria-gateway-1.0.0-SNAPSHOT.jar`，无需额外改动。
> 内存预算：网关 +256m → 之前算的 10.5~14GB 变为 **10.8~14.3GB**，16GB 机器仍然可行（但更紧，别再往这台机器加 JVM）。

### 9.6 （可选）真的要用动态发现时

按 §2.4 的第 2 条路做完（业务服务注册 `-http` 后缀的 HTTP 实例）之后：

```yaml
# 网关路由整表替换（注意 WS 那条要用 lb:ws://）
- id: user
  uri: lb://isegoria-user-http
- id: notify-ws
  uri: lb:ws://isegoria-notify-http
```

**验证**：

```bash
curl -s 127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=isegoria-user-http&username=nacos&password=xxx
# 期望：实例端口是 8201（而不是 50051）
```

**再加一层保护**：给 `lb://` 路由配健康检查，避免把请求打到刚下线的实例：

```yaml
spring:
  cloud:
    loadbalancer:
      health-check:
        initial-delay: 10s
        interval: 25s
```

---

## 十、验收清单（逐条打勾）

**功能**

- [ ] 网关 2 实例运行，Nacos 可见 `isegoria-gateway`；停一个实例业务无感（Nginx upstream 已配双实例）
- [ ] `/actuator/gateway/routes` 返回**非空**路由表（10+ 条，含 `notify-ws`）
- [ ] `/actuator/gateway/routes` 从公网访问不到（Nginx `location /actuator/ → 404`）
- [ ] 6 个服务的**全部免登录接口**都能以游客身份访问（帖子列表/详情/评论/板块/搜索/推荐流）
- [ ] 已登录请求：网关放行 → 后端 `StpUtil.getLoginIdAsLong()` 正常取到 loginId（会话透传 ✓）
- [ ] 未登录请求访问需登录接口：网关返回 `code=40100`，**后端日志里没有这条请求**
- [ ] 未匹配路由：返回 `{"code":40400}`，不是 Spring 白页
- [ ] 后端实例停掉时：返回 HTTP 503 + `{"code":50000}`（不是 500 堆栈）
- [ ] `/api/ws/**` 正常握手，前端 WS 稳定连接 > 30 分钟不断
- [ ] 图片上传（~5MB）成功，无 413
- [ ] CORS 预检通过，且 `Access-Control-Allow-Origin` 只出现 1 次
- [ ] 限流生效：超阈值返回 `code=42900`（同时确认服务内 `@RateLimit` 修好后也生效）
- [ ] 管理端：登录 + 用户列表 + 审核 + `reindex` 全通（含 §1.4 的 `admin/amin` 结论）
- [ ] 响应里 `id` 仍是字符串（Long→String 未被网关破坏）

**安全**

- [ ] 直连子服务（绕过网关）带合法 token：**被 Same-Token 拒绝**（P3 打开后）
- [ ] 网关的 Redis 与业务服务是**同一个实例、同一个 database**
- [ ] CORS 白名单是显式域名（不是 `*`），且 `allowCredentials` 只对白名单域名开放
- [ ] 网关未写任何业务逻辑（code review 确认）

**可观测与回滚**

- [ ] 统一访问日志含 traceId；能从网关一路查到下游（至少网关 → 服务）
- [ ] `/actuator/prometheus` 能抓到 SCG 指标
- [ ] 回滚演练：Nginx 切回直连 6 服务，业务恢复正常（≤ 5 分钟）

---

## 十一、风险与回滚

| 风险 | 症状 | 应对 |
|---|---|---|
| SCG 5.x 前缀写错 | 启动正常、`/actuator/gateway/routes` 为 `[]`、全站 404 | §2.1；用 `configprops` 核对前缀 |
| 误引 servlet 依赖 | 同上（应用以 SERVLET 模式启动） | 不引 `spring-boot-starter-web`；日志确认 `WebApplicationType: REACTIVE` |
| Sa-Token 版本错配 | "登录成功但网关一直 401"，日志有反序列化异常 | 两边同版本（§2.2 路线 A / B）；**不要**混版本 |
| Nacos 实例端口是 Dubbo 端口 | `lb://` 时约一半请求超时（打到 tri 端口） | P0 先看端口（§2.4）；用静态 URI 或 `-http` 后缀服务名 |
| SCA 的 nacos-client 3.1.1 打 2.3.2 服务端 | 启动日志 NacosException / 注册不上 | 钉到 2.4.2（§2.3）或升 Nacos 服务端 |
| 白名单漏配（whitelist 模式） | 游客功能大面积 401 | 用 blacklist 模式（§6.3）；上线前按 §6.3 的 grep 逐条核对 |
| WS 频繁重连 | 连接稳定 N 秒后断开 | WS 路由 `response-timeout: -1` + Nginx `proxy_read_timeout 3600s` |
| WS 连不上（握手 401） | SockJS `/api/ws/info` 401 | 白名单放行 `/api/ws/**`；过滤链**不改 Cookie** |
| CORS 双份头 | 浏览器报 multiple values | `DedupeResponseHeader`（§8.2） |
| 客户端 IP 变成网关 IP | `@RateLimit` 的 IP 维度全部命中同一个 key；日志里 IP 全是网关 | 配 `trusted-proxies`（§4.4） |
| 网关单点 | 网关挂 = 全站不可用 | 至少 2 实例 + Nginx upstream；健康检查；`server.shutdown: graceful` |
| 网关上写业务逻辑 | "顺手加个聚合接口" → 快速腐化 | 明令禁止；要聚合另立 BFF（phase9 §10） |
| 多一跳延迟 | 每次请求多 1~3ms | 可接受；网关与业务同机/同网段 |
| 内存预算 | +1 JVM，16GB 机器更紧 | 网关 `-Xmx256m`；见 §9.5 |

**回滚矩阵**

| 阶段 | 回滚动作 | 耗时 |
|---|---|---|
| P1~P4 | Nginx 恢复原 6 组 location（配置里已注释保留）+ reload | ≤ 1 分钟 |
| P2 加鉴权后出问题 | `gateway.auth.mode` 临时改成 `blacklist` 且 `protected-paths: []`（等于全放行），或直接注释掉 `SaReactorFilter` 这个 Bean | ≤ 1 分钟 |
| P3 Same-Token 后直连全挂 | `GATEWAY_SAME_TOKEN_ENABLED=false` 重启服务（compose 改一处，6 服务共用） | ≤ 3 分钟 |
| P4 限流误伤 | 去掉路由上的 `RequestRateLimiter` filter（配置改动，reload 网关） | ≤ 1 分钟 |
| 整体放弃 | 网关容器/进程停掉 + Nginx 回滚；业务服务不受影响 | ≤ 5 分钟 |

---

## 十二、排障速查表

| 现象 | 最可能的原因 | 怎么查 |
|---|---|---|
| **IDEA 里 pom 不识别 / 标红** | ① 模块不在父聚合 `<modules>` 里 → IDEA 只导入 reactor 模块，看不到它（表现为 pom 文件是普通 XML、没有 Maven 面板）；② 依赖缺 `version`；③ 模块 Language Level 不是 17+ | ① 已修：根 pom 的 `<modules>` 加了 `isegoria-gateway` → IDEA 点 "Reload All Maven Projects"（或右键根 pom → Maven → Reload project）；若仍不显示，右键 `isegoria-gateway/pom.xml` → **Add as Maven Project**；② 本地跑 `mvn -f isegoria-gateway/pom.xml -U validate`，报 `'dependencies.dependency.version' is missing` 就是它；③ `File → Project Structure → Modules → isegoria-gateway → Language level = 17` |
| 全站 404（所有 `/api/**`） | ① SCG 前缀写错（路由为空）② Nginx `proxy_pass` 带了 URI 把 `/api` 吃掉 ③ 误引 servlet 依赖 | `curl /actuator/gateway/routes`；检查 `proxy_pass http://isegoria_gateway;` 末尾没有 `/` |
| 只有某个服务 404 | 该服务的路由前缀写错/漏了；或 `StripPrefix` 误用 | 对照 §1.2 的表；看网关访问日志里 matched route id |
| 某服务偶发 502/超时 | 用了 `lb://` 但实例端口是 Dubbo 端口（§2.4） | 看 Nacos 实例端口；换成静态 URI 复测 |
| 登录了仍 401 | Sa-Token 版本错配；或网关 Redis 与业务服务不是同一个 | 比较两侧 `sa-token.*` 配置；`redis-cli -n 0 keys 'satoken:login:token:*'` 确认 key 在同一个库 |
| 游客接口 401 | 白名单漏配（whitelist 模式） | 网关日志里 `网关拦截未登录请求 path=...`；对照 §6.3 |
| 网关自己 40101 | `/api/admin/**` 命中了 user 路由（order 写反） | `curl /actuator/gateway/routes` 看 order；admin 组路由 order 必须小于通配 |
| WS 连不上 | 白名单没放行 `/api/ws/**`；或过滤链改了 Cookie | 看 SockJS `/api/ws/info` 的状态码；检查过滤器是否 mutate 了 headers 里的 Cookie |
| WS 每分钟断 | 全局 `response-timeout` 作用到 WS；或 Nginx `proxy_read_timeout` 没调 | WS 路由加 `metadata.response-timeout: -1`；Nginx 3600s |
| 上传 413 | 网关 `max-in-memory-size` / 服务 `max-request-size` / Nginx `client_max_body_size` 三者之一 | 从内到外逐个调，别一上来调 Nginx |
| CORS 报 multiple values | 网关 + 服务各写一份 | 加 `DedupeResponseHeader`（§8.2） |
| 日志里客户端 IP 全是网关 IP | `trusted-proxies` 没配 | §4.4；`curl /actuator/configprops \| grep trusted-proxies` |
| 网关起不来 / 端口占用 | 8100 被占（旧方案 phase11 也预留过 8100） | `ss -lntp \| grep 8100` |
| 启动报 `required a bean of type 'com.fasterxml.jackson.databind.ObjectMapper' that could not be found` | **仅车道 A/Boot 4 会出现**：Boot 4 只自带 Jackson 3，Jackson 2 的 ObjectMapper Bean 来自 `spring-boot-jackson2` 模块（默认不在依赖树） | 加 `org.springframework.boot:spring-boot-jackson2`；或改用 Jackson 3 的 `tools.jackson.databind.ObjectMapper`。车道 B（Boot 3.5.3）**不会**遇到 |
| 启动报 `Unable to find instance for bean ...` / SpEL 解析失败 | 路由 `key-resolver: "#{@xxx}"` 引用的 Bean 不存在（SCG 加载路由定义时就解析） | 确认 `RateLimitConfig` 里的 Bean 名与方法名一致；自检脚本会拦这类问题 |
| 限流"没反应"（阈值怎么调都不限） | ① 过滤器参数名写错（`replenishRate` 写成 `replenish-rate` 等）→ 静默不生效 ② 忘了 `key-resolver` ③ key 全是同一个（`trusted-proxies` 没配，客户端 IP 取不到） | `curl /actuator/gateway/routes` 看路由定义里 filters 是否带上；`redis-cli keys 'request_rate_limiter*'` 看有没有在计数 |
| 启动报 `Unknown property` / 路由为空 | 配置前缀写了老的 `spring.cloud.gateway.routes` | 改成 `spring.cloud.gateway.server.webflux.routes`（§2.1） |
| 编译报"程序包 org.springframework.boot.web.reactive.error 不存在" | **仅车道 A/Boot 4**：包已挪到 `webflux.error` | 车道 B（Boot 3.5.3）用 `web.reactive.error` 才对；升 Boot 4 时两个 import 一起改（§2.1） |
| Nacos 里看不到 `isegoria-gateway` | `register-enabled: false` 或 Nacos 认证失败 | 看启动日志的 Nacos 注册信息 |

---

## 附录 A：完整改动清单

**新增（网关工程）** —— ✅ 已落地 / ⬜ 待落地（P2 起）

```
✅ isegoria-gateway/pom.xml                                    外部 parent = Boot 4.0.8；已列入父聚合 <modules>
✅ isegoria-gateway/src/main/java/com/ruwei/gateway/IsegoriaGatewayApplication.java
✅ isegoria-gateway/src/main/resources/application.yml         路由 10 条 + 鉴权清单 + sa-token + user/user-admin 限流
✅ isegoria-gateway/src/main/resources/application-docker.yml  只覆盖 redis/nacos/trusted-proxies/CORS/hosts
✅ isegoria-gateway/src/main/java/com/ruwei/gateway/core/GatewayResult.java
✅ isegoria-gateway/src/main/java/com/ruwei/gateway/config/GatewayAuthProperties.java
✅ isegoria-gateway/src/main/java/com/ruwei/gateway/config/SaTokenGatewayConfig.java
✅ isegoria-gateway/src/main/java/com/ruwei/gateway/config/GatewayErrorConfig.java
✅ isegoria-gateway/src/main/java/com/ruwei/gateway/config/RateLimitConfig.java（ipKeyResolver / userKeyResolver）
✅ isegoria-gateway/src/main/java/com/ruwei/gateway/filter/SameTokenForwardFilter.java（已做阻塞隔离）
✅ isegoria-gateway/src/main/java/com/ruwei/gateway/filter/TraceIdFilter.java
✅ isegoria-gateway/src/main/java/com/ruwei/gateway/task/SameTokenRefreshJob.java
⬜ deploy/Dockerfile.gateway（**未采用**：已用 §9.1 做法 A 复用 deploy/Dockerfile 构建网关镜像，故不需要）
✅ docs/phase13-gateway-scg.md                ← 本文
```

> **当前状态（2026-09-18 晚）**：网关侧 **9 个 Java 类全部落地**（含 P2 鉴权 / P2 错误契约 / P3 Same-Token / P4 限流与 traceId），
> 且 9 个文件的 import 已逐条对真实 jar 核对通过（§2.1）。
> **部署接入也已落地**：`deploy/docker-compose.yml`（新增 app-gateway 服务、nginx depends_on、`GATEWAY_SAME_TOKEN_ENABLED`）、
> `deploy/scripts/start-all.sh`（gateway 放最前）、`deploy/.env.example`、两份 Nginx 配置里的**注释态切流块**。
> **剩**（都在业务侧，需要你拍板）：① Sa-Token 1.41.0 → 1.46.0；② `common` 的 Same-Token 校验过滤器（P3）；
> ③ Nginx 实际切流；④ `RateLimitAspect` 切点修复。
> 本地构建：`mvn -f isegoria-gateway/pom.xml -DskipTests clean package` → `java -jar isegoria-gateway/target/isegoria-gateway-1.0.0-SNAPSHOT.jar`。
>
> ⚠️ 注意 `SaTokenGatewayConfig` 一落地就**生效**了：网关现在会按 `gateway.auth.mode: blacklist` 做登录态粗筛。
> 若首次启动就要联调，先把 `protected-paths` 里想试的路径注释掉，或把 `mode` 临时改成 `whitelist` + 放行 `/**`
>（不推荐生产），避免"配错了还以为网关没起来"。

**修改（业务侧 / 部署）**

```
✅ pom.xml                                   <modules> 加 isegoria-gateway（车道 B：同时作为它的 parent，版本一起走）
🚫 pom.xml                                   Sa-Token 升级 —— **车道 B 不需要**（网关与业务服务同栈 1.41.0）
✅ isegoria-common/.../web/RateLimitAspect.java      切点已修：com.ruwei..controller..（§1.4 问题 1）
✅ isegoria-common/.../web/SameTokenCheckFilter.java （P3，默认关闭；另放行 OPTIONS 预检）
✅ 6 个服务 application.yml                    加 gateway.same-token.enabled: false（开关落在 yml，便于发现与覆盖）
✅ deploy/nginx/isegoria.conf                已加"网关切流块"（upstream + location /api/ + /api/ws 提示 + /actuator 禁入），
                                             **默认注释、现状不变**；启用/回滚见 §5.1
✅ deploy/nginx/isegoria.docker.conf         同上（upstream = app-gateway:8100）
✅ deploy/docker-compose.yml                 已新增 app-gateway 服务；x-app-env 加 GATEWAY_SAME_TOKEN_ENABLED
✅ deploy/scripts/start-all.sh               SERVICES / ORDER 已加 gateway（放最前）
✅ deploy/.env.example                       已加 GATEWAY_SAME_TOKEN_ENABLED=false（P3 再开）
✅ deploy/Dockerfile / README.md             注释口径由"6 个服务"改为"6 服务 + 网关"（SERVICE=gateway 可构建）
⬜ isegoria-user/.../UserManagerController.java 已改 admin（未提交）→ 确认前端/网关口径（§1.4 问题 2）
```

**不动**

```
6 个业务服务的 application.yml / application-docker.yml / 6 张业务表 / Dubbo 契约 / MQ 拓扑 / ES 索引 / Nginx 的 TLS 与静态资源段
```

## 附录 B：URL ↔ 服务 全量对照表

见 §1.2。**网关路由表与 Nginx 的 API 段都以它为准**；加新 Controller 时先更新这张表，再同步三处（网关 yml / Nginx / 本手册）。

## 附录 C：免登录接口清单（grep 依据）

| 路径 | 依据（源码） | 备注 |
|---|---|---|
| `POST /api/user/login` `/api/user/register` | 无鉴权注解 | 登录注册本身免登录 |
| `POST /api/admin/user/login` `/register` `/forgetPassword` `/api/admin/user/otherUserInfo` | 前三个无注解；`otherUserInfo` 是 `UserManagerController:122` `@SaIgnore` | 管理端登录/注册/找回密码/看他人资料 |
| `POST /api/post/list` | `PostController:155` `@SaIgnore` | 游客可看列表 |
| `GET /api/post/{id}` | `PostController:171` `@SaIgnore` | 游客可看详情 |
| `POST /api/post/{id}/view` | `PostController:198` `@SaIgnore` | 游客可浏览（不参与计分） |
| `POST /api/comment/list` `/api/comment/replies` | `CommentController:70/82` `@SaIgnore` | 游客可看评论 |
| `POST /api/boards/list` | `BoardController:56` `@SaIgnore` | 板块导航 |
| `GET /api/tag/hot` | `TagController:36` `@SaIgnore` | ★ **只有 /hot 公开**；`/list`、`/{id}` 受类级 `@SaCheckLogin` 保护 |
| `GET /api/search/post` | `SearchController` 无注解 | 游客可搜 |
| `POST /api/recommend/feed` | `RecController:47` `@SaIgnore` | ⚠️ 游客要能刷 feed |
| `/api/ws` `/api/ws/**` | notify 自己握手鉴权 | ⚠️ 必须放行 |
| `/api/admin/boards/list` | `BoardManagerController:44` `@SaIgnore` | 管理端板块列表（类级 `@SaCheckLogin`，此方法被 `@SaIgnore` 覆盖） |
| `/api/admin/tag/hot` | `TagManagerController:36` `@SaIgnore` | ★ 同样**只有 /hot**；`/list`、`/adminList`、`/{id}` 要登录（`/adminList` 还要 admin 角色） |

> 这份清单是**当前代码的快照**（2026-09-18 按全仓注解 grep 核对，含**类级**注解）。落地前请按 §6.3 的 grep 命令重跑一遍，别凭记忆写白名单。
> **两个易错点**：① `/api/tag/list`、`/api/admin/tag/list` **不是**公开接口（类级 `@SaCheckLogin` 生效，见 §1.4 问题 3）；② `/api/postLike/**` 未列入任何清单——`/count` 是纯公开的游客接口，混在需要登录的 `toggle` 前面，只能交给服务层。

## 附录 D：将来升级到车道 A（Boot 4）的差异表

**现状 = 车道 B（已落地）**。哪天要把网关升到 Boot 4（例如为了回到 OSS 支持期内），改下面这 6 处即可；
**建议时机与前置条件**：升级必须**两端一起升 Sa-Token**（网关 1.46+ 与 6 个业务服务同版本），
并接受"升级瞬间 Redis 里旧会话全部失效、用户需重登一次"。

| # | 项 | 现在（车道 B，已落地） | 升级后（车道 A） |
|---|---|---|---|
| 1 | 网关 pom 的 parent | `com.ruwei:isegoria-cloud`（`relativePath ../pom.xml`） | 换成外部 `spring-boot-starter-parent:4.0.8` + 显式 `<relativePath/>`；并**自带** Sa-Token BOM（因为不再是父 POM 的子模块语义） |
| 2 | Spring Cloud BOM | `2025.0.3` | `2025.1.3`（Oakwood，OSS 支持到 2027-07-31） |
| 3 | Sa-Token | `sa-token-reactor-spring-boot3-starter`（继承父 POM 的 1.41.0） | `sa-token-reactor-spring-boot4-starter:1.46.0`，**父 POM 的 `sa-token.version` 同步升 1.46.0**（6 个业务服务一起，回归一遍） |
| 4 | 父 POM | 不动 | `sa-token.version` 1.41.0 → 1.46.0（⚠️ 会清掉全部在线会话） |
| 5 | 代码 | — | `GatewayErrorConfig` 的两个 import 改回 `org.springframework.boot.webflux.error.*`（Boot 4 挪了包，§2.1） |
| 6 | 依赖 | — | **必须新增** `org.springframework.boot:spring-boot-jackson2`（Boot 4 只自带 Jackson 3，否则注入 `ObjectMapper` 失败，§2.1） |
| 7 | 工程结构 | `<modules>` 里有它、且它就是 parent | **只切继承、保留 `<modules>`**（IDE 识别与统一构建不受影响，§4.1） |

⚠️ 车道 B 的**已知代价**：Spring Cloud 2025.0.x 的 OSS 支持已于 **2026-06-30 结束**（不再有免费 CVE 修复）。
若这台机器会长期对公网开放，建议排期按上表迁移；只在内网/开发环境跑则可以放后面。

## 附录 E：一页纸决策表

> **版本决策已定（2026-09-18）：车道 B** —— 网关与 6 个业务服务同栈（Boot 3.5.3 + SC 2025.0.3 + Sa-Token 1.41.0），
> 业务服务零改动、版本对齐结构化；代价是 SC 2025.0.x 的 OSS 支持已结束。升级路径见附录 D。
> 下面这张表回答的是"功能范围要不要做全"：

| 你的情况 | 建议 |
|---|---|
| 只要灰度 / 可观测 / 统一跨域，暂时不想动鉴权 | 走 **P0 + P1 + P4**，跳过 P2/P3（路线 C） |
| 要"网关挡住未登录流量" | 走 **P0~P2**（路线 A 或 B） |
| 长期维护、不想一上线就踩过保的 release train | **路线 A**（网关 Boot 4 + Sa-Token 全栈 1.46.0） |
| 两周内要上线、不想动业务服务 | **路线 B**（全栈 Boot 3.5.3），之后排期迁 A |
| 内网端口已严格封闭（只绑 127.0.0.1、不进安全组） | P3 Same-Token 可延后；一旦多机/上 K8s **必须补** |
| 要动态扩缩容 | 先做 §2.4 第 2 条（业务服务注册 `-http` HTTP 实例），再把路由换 `lb://` |
| 要对外开放 API（第三方 key/签名/配额） | 本手册不覆盖，需在网关上加自定义 `GlobalFilter` + 独立密钥库（phase9 触发条件 3） |
