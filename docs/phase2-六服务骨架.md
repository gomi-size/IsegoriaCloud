# Phase 2 实施清单：六服务模块骨架

> 依据：《微服务拆分实施手册 · Dubbo 六服务版》§5 Phase 2（第 798–1123 行）
> 落地仓：`E:\GitHupProject\IsegoriaCloud`（**不是**手册里写的 `D:\frame\isegoria-cloud`，手册路径在本机不存在）
> 基线：Phase 1 收尾已完成（common / model / client 三模块）
> 出具：2026-09-12

---

## 一、Phase 2 的目标与边界

**目标**：一次性建出 6 个空壳服务，跑通「能启动 + 注册进 Nacos + 文档页可开」，为 Phase 3~8 逐个填业务类提供容器。

**做什么**（共 6 个模块 × 4 类文件）：

| 文件类型 | 数量 | 说明 |
|---|---|---|
| `pom.xml` | 6 | 继承父 POM，无版本号 |
| 启动类 `IsegoriaXxxApplication` | 6 | `@SpringBootApplication` + `@ComponentScan("com.ruwei")` + `@EnableDubbo` + `@MapperScan` |
| `application.yml` | 6 | 端口 / Dubbo / Sa-Token / MP / springdoc / RabbitMQ / cors |
| `config/ForumMqConfig` | 5 | 5 个 amqp 服务各一份（user 无 MQ 拓扑，仅需 rabbitmq 客户端配置） |
| 父 POM `<modules>` | 1 处改 | 解开 6 行注释 |
| 父 POM `<dependencyManagement>` | 2 处增 | springdoc 版本收口、nacos-client 版本收口（**新增项，见第三节 H2/H1**） |

**不做什么**：不建 controller / service / mapper / entity（那是 Phase 3~8）；不建 Mapper XML；不建 `application-local.yml`（Phase 4 post 才需要 COS 凭据）；不引 Nginx（Phase 9）。

---

## 二、开工前基线核对

### 2.1 已就位（Phase 1 产物，本次不动）

| 项 | 状态 |
|---|---|
| 父 POM：Boot 3.5.3 + Sa-Token BOM + Dubbo BOM 3.3.0 | ✅ |
| `isegoria-common`（17 个类：core 6 / web 7 / mybatis 3 / mq 1） | ✅ |
| `isegoria-model`（80 个类：entity 20 / dto / vo / enums / mq 11） | ✅ |
| `isegoria-client`（5 个 `InnerXxxService` 接口） | ✅ |
| `ForumMqConstants`（EXCHANGE=`forum.exchange`、DLX=`forum.dlx`、11 RK、11 queue 名） | ✅ |
| 6 个服务目录 | ❌ 全部不存在（本次创建） |

### 2.2 环境依赖（缺一项 Phase 2 验证就过不了）

| 组件 | 端口 | 状态判断依据 |
|---|---|---|
| MySQL 8（`frorum`） | 3306 | 已在跑（单体在用） |
| Redis | 6379 | 已在跑 |
| RabbitMQ | 5672 | 已在跑 |
| Elasticsearch 8 | 9200 | 已在跑（rec 用） |
| **Nacos 2.3.2** | 8848 / 9848 / **9849** | **需先启动**，且端口映射要补 9849（见 H4） |
| 本机 Maven CLI | — | `mvn` 未在 PATH、仓内无 `mvnw` → **构建验证走 IDEA 内置 Maven** |

### 2.3 版本基线（仓库当前状态）

- 分支：**`master`**（Phase 0 要求的 `feature/microservice-split` 尚未创建）
- 未提交变更：6 个文件（Phase 1 的 `SaTokenConfigure` / `AsyncConfig` / `QueryWrapperUtils` / `PageRequest` 迁移）仍处于 staged 状态
- 无 `.gitignore`（`.idea/`、`.workbuddy/` 处于未跟踪状态）

---

## 三、⚠️ 手册 Phase 2 的 6 处硬伤（必须先修，否则骨架起不来）

### H1 【阻断级】缺 `dubbo-nacos-spring-boot-starter`，`nacos://` 注册必失败

手册 §2.1 的 pom 模板只声明了 `dubbo-spring-boot-starter`。但本机取证实测：

```
org.apache.dubbo:dubbo:3.3.0 的 pom 中：
    dubbo-registry-nacos   3.3.0  scope=compile  optional=true   <-- optional，不传递
    dubbo-configcenter-nacos / dubbo-metadata-report-nacos 同样 optional=true

dubbo-3.3.0.jar 内：
    org/apache/dubbo/registry/nacos/*  → 24 个条目（适配层被 fat-jar 打进来了）
    com/alibaba/nacos/*                → 0 个条目（nacos-client 没进来）
```

即：Dubbo 核心 jar 自带 `NacosRegistryFactory` SPI 实现（`META-INF/dubbo/internal/org.apache.dubbo.registry.RegistryFactory` 里确有 `nacos=...NacosRegistryFactory`），**但运行时需要的 `nacos-client` 一个类都没有**。结果不是「找不到扩展」而是实例化时 `NoClassDefFoundError: com/alibaba/nacos/api/NacosFactory`，启动直接挂。

**修正**：6 个服务 pom 都补 `org.apache.dubbo:dubbo-nacos-spring-boot-starter`（版本由 Dubbo BOM 管理，无需写版本号；本机 m2 已有 3.3.0，其传递依赖为 `com.alibaba.nacos:nacos-client:2.4.1`）。

### H2 【阻断级】knife4j 锁死 springdoc 2.3.0，与 Boot 3.5.3 不兼容；手册的兜底方案无效

本机取证实测：

```
knife4j 4.5.0  →  knife4j-springdoc-openapi-jakarta.version = 2.3.0
knife4j 4.4.0  →  knife4j-springdoc-openapi-jakarta.version = 2.3.0   ← 手册建议降到的版本
```

- springdoc **不是** Spring Boot BOM 管理的构件，故 knife4j 声明的 2.3.0 会原样生效；
- springdoc 2.3.0 面向 Boot 3.2，在 Boot 3.4+ 上存在已知的 `ControllerAdviceBean` / `NoSuchMethodError` 类崩溃，`/v3/api-docs` 与 `/doc.html` 废掉；
- 手册 §1.1 适配表写的兜底「或把 knife4j 降至 4.4.0」**不成立** —— 4.4.0 锁的同样是 2.3.0。

**修正**：在父 POM `dependencyManagement` 里显式收口 `springdoc-openapi-starter-webmvc-ui` 到 **2.8.10**（本机 m2 已缓存 2.8.10 / 2.8.5，无需联网）。只收口 ui 一个坐标即可，其 pom 会把 `-webmvc-api` / `-common` 一并拉到 2.8.10。

> 残留风险：knife4j 4.5.0 的 `doc.html` 静态 UI 与 springdoc 2.8.x 的组合未经官方验证。若 6 个 `doc.html` 出现白屏/404，兜底顺序是：① 换 `springdoc-openapi-starter-webmvc-ui` 2.8.5；② 直接弃用 knife4j UI，改用 springdoc 自带 `/swagger-ui.html`（业务零影响，只是换了张皮）。

### H3 【逻辑矛盾】user 服务被标"无 amqp / 可删 rabbitmq 段"，但 Phase 3 要求它发 MQ

- 手册 §2.1 差异表：`user | — | 基础模板`（无额外依赖）
- 手册 §2.3 yml 注释：`rabbitmq: # user 服务可去掉此段；其余 5 服务保留`
- 但手册 §3.2 ③ 明确要求改造 UserServiceImpl：`rabbitTemplate.convertAndSend(EXCHANGE, RK_ES_USER_PROFILE, new UserProfileMessage(...))`

三处自相矛盾。**修正**：user 也加 `spring-boot-starter-amqp` + rabbitmq 配置段 → **6 个服务全部持有 amqp 与 rabbitmq 配置**，`ForumMqConfig` 仍是 5 份（user 只发不收，不需要交换机声明；发布方由 RabbitTemplate 直接发，缺失的 exchange 由消费方或 Phase 4 的 post 声明）。

> 更稳的做法：user 也建一份 `ForumMqConfig`（仅声明 exchange + converter，不声明队列），确保 Phase 3 发消息时 exchange 必然存在。**建议采纳**，见第五节 5.5。

### H4 【环境】Nacos 容器端口映射少一个 9849

手册 §3.1 的命令：`-p 8848:8848 -p 9848:9848`。Nacos 2.x 有**两个**偏移端口：

| 用途 | 端口 | 是否必需 |
|---|---|---|
| 控制台 / HTTP API | 8848 | 必需 |
| 客户端 gRPC（注册、心跳、订阅） | 9848（8848+1000） | 必需 |
| 服务端 gRPC（集群同步） | 9849（8848+1001） | standalone 下也建议映射，缺失时启动日志会报端口绑定异常 |

**修正命令**：

```powershell
docker run -d --name nacos -e MODE=standalone `
  -p 8848:8848 -p 9848:9848 -p 9849:9849 `
  nacos/nacos-server:v2.3.2
```

控制台：http://localhost:8848/nacos （nacos / nacos）

> 版本兼容性已核实：Nacos 官方规则为「2.X 服务端兼容所有 2.X 客户端」，故 服务端 2.3.2 + `nacos-client 2.4.1` 成立。真正的雷区是「服务端 ≥ 2.5.0 强制要求客户端 ≥ 2.5.0」，本方案不涉及。

### H5 【隐蔽】`dubbo-spring-boot-starter` 自带 `spring-boot-starter:2.7.18`

实测其 pom：`org.springframework.boot:spring-boot-starter :2.7.18 [compile]`。

这**不会**造成降级 —— 父 POM 已 import `spring-boot-dependencies:3.5.3`，dependencyManagement 的优先级高于传递依赖自带的版本号，最终会解析成 3.5.3。但这是「靠机制兜住」而非「显式声明」，必须验证一次：

```powershell
# IDEA Terminal 里执行（需先把 Maven Wrapper 或本地 mvn 准备好）
mvn -pl isegoria-user dependency:tree -Dincludes=org.springframework.boot:spring-boot-starter
```

期望输出 `org.springframework.boot:spring-boot-starter:jar:3.5.3`；若出现 2.7.18，则在父 POM 显式加一条 `spring-boot-starter` 的 dependencyManagement 条目压掉。

> 用哪个 starter？手册选 `dubbo-spring-boot-starter`（统一 starter），不用 `dubbo-spring-boot-starter3`。依据：① dubbo-bom 3.3.0 的托管清单里**没有** starter3，用它得手写版本号；② 实测其 autoconfigure jar 同时含 `META-INF/spring.factories` 与 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，Boot 2/3 双兼容；③ 本机 m2 只下载了统一 starter 3.3.0（说明既有工程走的就是这条路）。

### H6 【流程】Phase 0 的分支没做

手册 §3.2 要求 `git checkout -b feature/microservice-split`，当前却在 `master`，且 Phase 1 的 6 个文件已 staged 未提交。

**修正**：先落 Phase 1 提交再开 Phase 2（建议顺序见第七节）。

### H7 【补充】启动类漏标 `@EnableScheduling`（user 需要）

手册 §2.2 注：「有定时任务的服务（post/rec/interaction）启动类补 `@EnableScheduling`」—— 漏了 **user**。手册 §3.3 的 `SensitiveWordLoader` 带 `@Scheduled(fixedDelay = 300_000)`，落在 `com.ruwei.isegoriauser.config`，而 §8 联调清单第 2324 行也写着「user 的 SensitiveWordLoader 依赖 post 的 InnerSensitiveWord」。

**修正**：user / post / interaction / rec 四个启动类加 `@EnableScheduling`（social / notify 暂不加，落地时按需补）。

---

## 四、改动总清单

### 4.1 修改（2 个文件）

| 文件 | 改动 |
|---|---|
| `pom.xml` | ① `<modules>` 解开 6 行；② `<properties>` 增 `springdoc.version=2.8.10`、`nacos-client.version=2.4.2`；③ `<dependencyManagement>` 增 springdoc 与 nacos-client 两条收口 |
| `.gitignore`（新建，非修改） | 忽略 `.idea/`、`*.iml`、`target/`、`.workbuddy/tmp/` |

### 4.2 新建（30 个文件）

| 模块 | 文件 |
|---|---|
| `isegoria-user/` | `pom.xml`、`src/main/java/com/ruwei/isegoriauser/IsegoriaUserApplication.java`、`src/main/resources/application.yml`、`src/main/java/com/ruwei/isegoriauser/config/ForumMqConfig.java` |
| `isegoria-post/` | 同上（包 `com.ruwei.isegoriapost`，类 `IsegoriaPostApplication`），另有 `config/ForumMqConfig.java` |
| `isegoria-interaction/` | 包 `com.ruwei.isegoriainteraction`，类 `IsegoriaInteractionApplication` |
| `isegoria-social/` | 包 `com.ruwei.isegoriasocial`，类 `IsegoriaSocialApplication` |
| `isegoria-notify/` | 包 `com.ruwei.isegorianotify`，类 `IsegoriaNotifyApplication` |
| `isegoria-rec/` | 包 `com.ruwei.isegoriarec`，类 `IsegoriaRecApplication` |

> 每服务 4 个文件 × 6 = 24，加 `.gitignore`、父 POM 2 处增项；总计约 25 个新文件、1 个改动文件。

---

## 五、逐文件规格

### 5.1 父 POM（改）

**① `<modules>` 解开 6 行**（当前第 18–26 行是注释块）：

```xml
    <modules>
        <module>isegoria-common</module>
        <module>isegoria-model</module>
        <module>isegoria-client</module>
        <module>isegoria-user</module>
        <module>isegoria-post</module>
        <module>isegoria-interaction</module>
        <module>isegoria-social</module>
        <module>isegoria-notify</module>
        <module>isegoria-rec</module>
    </modules>
```

**② `<properties>` 增两项**：

```xml
        <springdoc.version>2.8.10</springdoc.version>
        <nacos-client.version>2.4.2</nacos-client.version>
```

**③ `<dependencyManagement>` 增两条**（放在 Dubbo BOM 之后、MyBatis-Plus 之前，保持「BOM 区 → 第三方坐标区 → 工程内模块区」的现状层次）：

```xml
            <!-- springdoc 版本收口（H2）：knife4j 4.5.0 与 4.4.0 都锁死 springdoc 2.3.0，
                 而 springdoc 不是 Boot BOM 管理的构件，不显式收口会停留在 2.3.0，
                 该版本面向 Boot 3.2，在 3.5.3 下 /v3/api-docs 与 /doc.html 不可用 -->
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>${springdoc.version}</version>
            </dependency>

            <!-- nacos-client 版本收口（H1/H4）：dubbo-nacos-spring-boot-starter 声明 2.4.1，
                 统一到本机已缓存的 2.4.2；与服务端 Nacos 2.3.2 同属 2.x，官方兼容规则成立 -->
            <dependency>
                <groupId>com.alibaba.nacos</groupId>
                <artifactId>nacos-client</artifactId>
                <version>${nacos-client.version}</version>
            </dependency>
```

### 5.2 服务 POM（6 份，以 user 为模板）

在手册模板基础上：**删除**手册 `spring-boot-starter-amqp` 缺失、**增加** `dubbo-nacos-spring-boot-starter`。另注意 common 已传递 `spring-boot-starter-web` / `-data-redis` / MP starter / sa-token / hutool，服务 POM 里的重复声明是**显式冗余**（Maven 会去重），保留手册写法以便逐服务对照依赖视图。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.ruwei</groupId>
        <artifactId>isegoria-cloud</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>isegoria-user</artifactId>
    <name>isegoria-user</name>
    <description>用户与认证服务：注册/登录/个人信息/管理端用户管理（8201 / dubbo 50051）</description>

    <dependencies>
        <!-- 公共 + 契约 -->
        <dependency>
            <groupId>com.ruwei</groupId>
            <artifactId>isegoria-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.ruwei</groupId>
            <artifactId>isegoria-client</artifactId>
        </dependency>

        <!-- Web / 校验 / AOP -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>

        <!-- Redis（Sa-Token 会话 + 业务缓存） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- MyBatis-Plus + MySQL -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-jsqlparser-4.9</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- MQ 事件总线（H3：user 也要发 es.user.profile 消息，故六个服务统一持有 amqp） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>

        <!-- Dubbo：核心 starter + Nacos 注册中心客户端（H1：缺后者则 nacos:// 注册 NoClassDefFoundError） -->
        <dependency>
            <groupId>org.apache.dubbo</groupId>
            <artifactId>dubbo-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.dubbo</groupId>
            <artifactId>dubbo-nacos-spring-boot-starter</artifactId>
        </dependency>

        <!-- 工具 / 文档 -->
        <dependency>
            <groupId>cn.hutool</groupId>
            <artifactId>hutool-all</artifactId>
        </dependency>
        <dependency>
            <groupId>com.github.xiaoymin</groupId>
            <artifactId>knife4j-openapi3-jakarta-spring-boot-starter</artifactId>
        </dependency>
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
                <configuration>
                    <mainClass>com.ruwei.isegoriauser.IsegoriaUserApplication</mainClass>
                    <!-- 注意：jvmArguments 仅对 mvn spring-boot:run 生效；
                         IDEA 直接启动需在 Run Configuration 的 VM options 里加同样的 add-opens（见第七节） -->
                    <jvmArguments>
                        --add-opens java.base/java.lang=ALL-UNNAMED
                        --add-opens java.base/java.util=ALL-UNNAMED
                        --add-opens java.base/java.io=ALL-UNNAMED
                        --add-opens java.base/java.lang.reflect=ALL-UNNAMED
                        --add-opens java.base/java.math=ALL-UNNAMED
                    </jvmArguments>
                </configuration>
                <executions>
                    <execution>
                        <id>repackage</id>
                        <goals><goal>repackage</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

**其余 5 份的差异**（`artifactId` / `name` / `description` / `<mainClass>` + 下表）：

| 服务 | artifactId | mainClass | 额外依赖 | 手册值 vs 本次修正 |
|---|---|---|---|---|
| user | `isegoria-user` | `com.ruwei.isegoriauser.IsegoriaUserApplication` | — | ⚠️ 本次**增** `spring-boot-starter-amqp`（H3） |
| post | `isegoria-post` | `com.ruwei.isegoriapost.IsegoriaPostApplication` | `cos_api` | 同手册（amqp 已含） |
| interaction | `isegoria-interaction` | `com.ruwei.isegoriainteraction.IsegoriaInteractionApplication` | — | 同手册 |
| social | `isegoria-social` | `com.ruwei.isegoriasocial.IsegoriaSocialApplication` | — | 同手册 |
| notify | `isegoria-notify` | `com.ruwei.isegorianotify.IsegoriaNotifyApplication` | `spring-boot-starter-websocket` | 同手册 |
| rec | `isegoria-rec` | `com.ruwei.isegoriarec.IsegoriaRecApplication` | `spring-boot-starter-data-elasticsearch` | 同手册 |

> 全部 6 份都要加 `dubbo-nacos-spring-boot-starter`（H1）；`spring-boot-starter-amqp` 6 份都有。

### 5.3 启动类（6 份，以 user 为模板）

```java
package com.ruwei.isegoriauser;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 用户与认证服务（HTTP 8201 / Dubbo tri 50051）。
 *
 * <p>组件扫描说明：{@code @ComponentScan("com.ruwei")} 用于把 isegoria-common 里的
 * CorsConfig / SaTokenConfigure / GlobalExceptionHandler / AsyncConfig / RateLimitAspect
 * 与 isegoria-client 的 innerservice 接口一并扫入。接口无 @Component 注解，扫到无副作用；
 * 其他服务的实现类不在本服务 classpath，不会被误扫。
 *
 * <p>{@code @EnableScheduling} 覆盖 SensitiveWordLoader 的 5 分钟词表兜底刷新。
 */
@SpringBootApplication
@ComponentScan("com.ruwei")
@EnableDubbo
@EnableScheduling
@MapperScan("com.ruwei.isegoriauser.mapper")
public class IsegoriaUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(IsegoriaUserApplication.class, args);
    }
}
```

**各服务替换点**：

| 服务 | 包名 | 类名 | `@MapperScan` | `@EnableScheduling` |
|---|---|---|---|---|
| user | `com.ruwei.isegoriauser` | `IsegoriaUserApplication` | `com.ruwei.isegoriauser.mapper` | ✅（H7） |
| post | `com.ruwei.isegoriapost` | `IsegoriaPostApplication` | `com.ruwei.isegoriapost.mapper` | ✅ |
| interaction | `com.ruwei.isegoriainteraction` | `IsegoriaInteractionApplication` | `com.ruwei.isegoriainteraction.mapper` | ✅ |
| social | `com.ruwei.isegoriasocial` | `IsegoriaSocialApplication` | `com.ruwei.isegoriasocial.mapper` | — |
| notify | `com.ruwei.isegorianotify` | `IsegoriaNotifyApplication` | `com.ruwei.isegorianotify.mapper` | — |
| rec | `com.ruwei.isegoriarec` | `IsegoriaRecApplication` | `com.ruwei.isegoriarec.mapper` | ✅ |

> ⚠️ Phase 2 阶段 `mapper` 包**还不存在**。`@MapperScan` 指向空包只会让 MyBatis 打一条
> `No MyBatis mapper was found in '[...]' package` 警告，**不会启动失败**，属预期。Phase 3 起随类落地自然消除。

### 5.4 application.yml（6 份，以 user 为基线）

相对手册 §2.3 模板的三处修正：
1. **补 `cors.allowed-origins` 段**（common 的 `CorsConfig` 用 `@ConfigurationProperties(prefix="cors")` 读取，缺省是空 List → 前端一律被拒）
2. **`mybatis-plus.global-config` 提到与 `configuration` 平级** —— 旧单体的 yml 里它被错误地缩进到了 `configuration` 之下（`application.yml` 第 97–101 行），导致**逻辑删除配置实际未生效**。新服务不要再复制这个错。
3. **rabbitmq 段 6 个服务全保留**（H3）

```yaml
server:
  port: 8201                # user=8201 post=8202 interaction=8203 social=8204 notify=8205 rec=8206
  servlet:
    context-path: /api
  error:
    include-stacktrace: always
    include-message: always

spring:
  application:
    name: isegoria-user     # 与 dubbo.application.name 一致
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/frorum?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: 123456         # 敏感配置建议后续移入 application-local.yml
  data:
    redis:
      host: localhost
      port: 6379
      password:
      database: 0
      timeout: 5000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0
          max-wait: -1ms
  rabbitmq:
    host: localhost
    port: 5672
    username: root
    password: 123456
    publisher-confirm-type: correlated
    publisher-returns: true
    listener:
      simple:
        acknowledge-mode: manual
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000ms
          multiplier: 2.0

# ===== 跨域白名单（CorsConfig 读取；严禁 *，allowCredentials=true 下等同任意站点可带 Cookie） =====
cors:
  allowed-origins:
    - http://localhost:3000
    - http://localhost:5173
    - http://localhost:5174
    - http://127.0.0.1:3000

# ===== Dubbo（六服务差异仅 protocol.port：50051~50056）=====
dubbo:
  application:
    name: isegoria-user
  registry:
    address: nacos://127.0.0.1:8848?username=nacos&password=nacos
    register-mode: instance
  protocol:
    name: tri
    port: 50051
  consumer:
    timeout: 5000
    check: false            # 启动不强制依赖方在线，避免启动顺序耦合
  provider:
    timeout: 5000

# ===== Sa-Token（六服务必须逐字一致——共享登录态的根基）=====
sa-token:
  token-name: isegoria
  timeout: 7200
  active-timeout: 1800
  is-concurrent: true
  is-share: true
  token-style: uuid
  cookie:
    path: /
    secure: false
    http-only: true
    same-site: Lax

# ===== MyBatis-Plus（六服务一致；注意 global-config 与 configuration 平级，勿缩进进 configuration）=====
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
    map-underscore-to-camel-case: false
  global-config:
    db-config:
      logic-delete-field: isDelete
      logic-delete-value: 1
      logic-not-delete-value: 0

logging:
  level:
    com.ruwei: debug

springdoc:
  swagger-ui:
    path: /swagger-ui.html
    tags-sorter: alpha
    operations-sorter: alpha
  api-docs:
    path: /v3/api-docs
  group-configs:
    - group: 'default'
      paths-to-match: '/**'
      packages-to-scan: com.ruwei.isegoriauser.controller

knife4j:
  enable: true
  setting:
    language: zh_cn
```

**各服务 yml 差异表**：

| 服务 | `server.port` | `dubbo.protocol.port` | `application.name` / `dubbo.application.name` | `packages-to-scan` | 额外段 |
|---|---|---|---|---|---|
| user | 8201 | 50051 | `isegoria-user` | `com.ruwei.isegoriauser.controller` | — |
| post | 8202 | 50052 | `isegoria-post` | `com.ruwei.isegoriapost.controller` | `spring.servlet.multipart`（5MB/5MB，图片上传） |
| interaction | 8203 | 50053 | `isegoria-interaction` | `com.ruwei.isegoriainteraction.controller` | — |
| social | 8204 | 50054 | `isegoria-social` | `com.ruwei.isegoriasocial.controller` | — |
| notify | 8205 | 50055 | `isegoria-notify` | `com.ruwei.isegorianotify.controller` | —（WebSocket 端点 `/api/ws` 由 Phase 7 的 WebSocketConfig 注册） |
| rec | 8206 | 50056 | `isegoria-rec` | `com.ruwei.isegoriarec.controller` | `spring.elasticsearch.uris: http://localhost:9200` |

**后续 Phase 才补的 yml 段**（Phase 2 不写，避免无对应 Bean 造成误解）：

| 段 | 目标服务 | 何时加 |
|---|---|---|
| `storage.type: cos` + `cos.client.{host,secret-id,secret-key,region,bucket}` | post（放 `application-local.yml`） | Phase 4 |
| `spring.profiles.active: local` | post | Phase 4 |
| `spring.profiles.active: sorce` + `application-sorce.yml`（`rec.*` 精排/召回参数） | rec | Phase 8 |

> ⚠️ 安全提示：旧单体的 `application-local.yml` 里 COS 密钥是**明文**（`secret-id` / `secret-key`）。Phase 4 迁移时建议改为环境变量占位符并把该文件加入 `.gitignore`。

### 5.5 `config/ForumMqConfig`（拟建 6 份，含 user）

手册 §2.4 说「5 个 amqp 服务各自持有」，队列声明按需在后续 Phase 展开。本节按 H3 的建议给 **user 也建一份**（只声明 exchange + converter），理由：Phase 3 的 `UserServiceImpl` 要发 `es.user.profile`，若 exchange 尚不存在，发布方无处可发（RabbitMQ 对未声明 exchange 的发布是直接丢弃消息，不报错，问题极隐蔽）。

```java
package com.ruwei.isegoriapost.config;   // 各服务改包名：isegoriauser / isegoriainteraction / ...

import com.ruwei.common.mq.ForumMqConstants;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * forum 事件总线拓扑：交换机声明 + JSON 消息转换器。
 *
 * <p>声明幂等（durable 已存在不重复建），多服务重复声明无害。
 * 交换机声明放在发布方也成立，避免「先发布、后由消费方建拓扑」期间消息静默丢失。
 * 各服务自己的消费队列（含 DLQ 参数与绑定）在对应 Phase 落地时追加到本类。
 */
@Configuration
public class ForumMqConfig {

    /** 死信交换机（direct，durable） */
    @Bean
    public DirectExchange forumDlx() {
        return ExchangeBuilder.directExchange(ForumMqConstants.DLX).durable(true).build();
    }

    /** 主事件交换机（topic，durable） */
    @Bean
    public TopicExchange forumExchange() {
        return ExchangeBuilder.topicExchange(ForumMqConstants.EXCHANGE).durable(true).build();
    }

    /** JSON 序列化：消息体为 isegoria-model 的 model.mq POJO；Bean 存在时 RabbitTemplate 自动采用 */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ---- 队列声明示例（仅消费方声明，Phase 7 的 notify 展开 8 组）----
    // @Bean
    // Queue notifyLikeQueue() {
    //     return QueueBuilder.durable(ForumMqConstants.Q_NOTIFY_LIKE)
    //             .withArgument("x-dead-letter-exchange", ForumMqConstants.DLX)
    //             .withArgument("x-dead-letter-routing-key", ForumMqConstants.Q_NOTIFY_LIKE + ".dlq")
    //             .build();
    // }
    // @Bean
    // Binding notifyLikeBinding() {
    //     return BindingBuilder.bind(notifyLikeQueue())
    //             .to(forumExchange()).with(ForumMqConstants.RK_EVT_LIKE);
    // }
}
```

### 5.6 `.gitignore`（新建，可选但建议）

```gitignore
# IDE
.idea/
*.iml
*.ipr
*.iws

# 构建产物
target/
**/target/

# 本会话临时产物（脚本 / 探针 / 日志）
.workbuddy/tmp/
```

> `.workbuddy/memory/` 是否入库由你定（目前整个 `.workbuddy/` 未跟踪）。

---

## 六、端口 / 包名 / 依赖 一览

| 服务 | HTTP | Dubbo tri | 包名 | 主域 | 独有依赖 |
|---|---|---|---|---|---|
| user | 8201 | 50051 | `com.ruwei.isegoriauser` | 注册/登录/资料/管理端用户 | — |
| post | 8202 | 50052 | `com.ruwei.isegoriapost` | 帖子/评论/分享/浏览历史/敏感词/管理端帖子 | `cos_api` |
| interaction | 8203 | 50053 | `com.ruwei.isegoriainteraction` | 点赞（含表达态） | — |
| social | 8204 | 50054 | `com.ruwei.isegoriasocial` | 关注/板块关注 | — |
| notify | 8205 | 50055 | `com.ruwei.isegorianotify` | 站内通知 + WebSocket | `spring-boot-starter-websocket` |
| rec | 8206 | 50056 | `com.ruwei.isegoriarec` | 推荐召回/精排 | `spring-boot-starter-data-elasticsearch` |

公共依赖（全部服务）：`isegoria-common`、`isegoria-client`、`spring-boot-starter-web` / `validation` / `aop` / `data-redis` / `amqp` / `test`、`mybatis-plus-spring-boot3-starter` + `mybatis-plus-jsqlparser-4.9` + `mysql-connector-j`、`dubbo-spring-boot-starter` + `dubbo-nacos-spring-boot-starter`、`hutool-all`、`knife4j-openapi3-jakarta-spring-boot-starter`、`lombok`。

---

## 七、执行与验收步骤

### 7.1 执行顺序（建议）

```
① 开分支并落 Phase 1 提交
   git checkout -b feature/microservice-split
   git commit -m "refactor(phase1): 包名对齐手册 + PageRequest 下沉 model + common 三项迁移"

② 落 Phase 2 改动（本次的 2 改 + 25 新）

③ IDEA → Maven 面板 → Reload All Maven Projects（10 个模块全部出现）

④ 本地编译
   mvn clean compile            # 10 模块全绿

⑤ 启 Nacos（含 9849）
   docker run -d --name nacos -e MODE=standalone -p 8848:8848 -p 9848:9848 -p 9849:9849 nacos/nacos-server:v2.3.2

⑥ 逐个启动 6 个服务（先 user 一个，验通再起其余）

⑦ 验收（下表）
```

### 7.2 验收清单

- [ ] IDEA Maven 面板出现 10 个模块（common / model / client + 6 服务）
- [ ] `mvn clean compile` 通过
- [ ] `mvn -pl isegoria-user dependency:tree -Dincludes=org.springframework.boot:spring-boot-starter` 解析为 **3.5.3**（H5）
- [ ] 6 个服务可启动，各占 8201~8206，无 `NoClassDefFoundError: com/alibaba/nacos`
- [ ] Nacos 控制台「服务列表」出现 6 个应用实例（http://localhost:8848/nacos）
- [ ] `http://localhost:8201/api/doc.html` 等 6 个文档页正常打开，`/api/v3/api-docs` 返回 JSON（H2 的验收点）
- [ ] 旧单体（旧仓 `E:\GitHupProject\ruwei-IsegoriaForum`，8188）仍可启动，作回归对照

### 7.3 IDEA 运行配置必做项（手册没写，但会直接卡住）

手册 §2.1 把 `--add-opens` 只写在 `spring-boot-maven-plugin` 的 `jvmArguments` 里，**该配置只对 `mvn spring-boot:run` 生效**。IDEA 直接点 Run 时必须在每个服务的 Run Configuration → VM options 里补上：

```
--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.math=ALL-UNNAMED
```

否则 JDK 17 强封装（JPMS）下 Sa-Token / Hutool / springdoc 的反射会抛 `InaccessibleObjectException`。旧单体 pom 里有一段完全相同的注释警告，可直接复用其措辞。

---

## 八、风险与回滚

| # | 风险 | 概率 | 影响 | 应对 |
|---|---|---|---|---|
| R1 | knife4j 4.5.0 + springdoc 2.8.10 组合下 `doc.html` 白屏/404 | 中 | 仅文档页不可用，业务无影响 | 降 springdoc 至 2.8.5；仍不行则改用 springdoc 自带 `/swagger-ui.html` |
| R2 | Dubbo 3.3.0 官方兼容表未覆盖 Boot 3.5.x | 低 | 启动期 Bean 装配异常 | 换 `dubbo-spring-boot-starter3:3.3.0`（需手写版本号，BOM 未托管） |
| R3 | `spring-boot-starter` 被 Dubbo 拉回 2.7.18 | 低 | 大面积 API 不兼容 | 按 H5 验证；异常则在父 POM 显式收口 3.5.3 |
| R4 | 6 个服务同时启动吃内存（每个约 400~600MB） | 中 | 本机卡顿 | 分两批启动，Phase 2 只需各服务各自能单独起来即算通过 |
| R5 | `@MapperScan` 指向不存在的包 | 高（预期内） | 仅 WARN 日志 | 无需处理，Phase 3 自动消除 |
| R6 | Nacos 未启动时启动服务 | 中 | 注册失败，Dubbo 反复重试刷日志 | 先起 Nacos；`dubbo.consumer.check:false` 已保证不阻断启动 |

**回滚**：Phase 2 只新增目录 + 父 POM 两处增项，`git checkout -- pom.xml && rm -rf isegoria-{user,post,interaction,social,notify,rec}` 即可完全回退，不影响 Phase 1 成果。

---

## 九、待你拍板

| # | 事项 | 我的建议 |
|---|---|---|
| D1 | Phase 1 的 6 个 staged 文件是否先在 `feature/microservice-split` 上提交，再动 Phase 2 | **建议先提交**，Phase 1/Phase 2 分成两个 commit，出问题好回退 |
| D2 | user 是否也建 `ForumMqConfig`（手册只说 5 份） | **建议建**（仅 exchange + converter），理由见 H3/5.5 |
| D3 | `.gitignore` 是否纳入本次（`.workbuddy/memory/` 要不要入库） | **建议纳入 `.idea/` `target/` `.workbuddy/tmp/`**；memory 是否入库你定 |
| D4 | `nacos-client` 收口到 2.4.2（本机已有）还是保留 starter 声明的 2.4.1（需联网下载） | **建议 2.4.2**，少一次下载且与服务端兼容规则成立 |
| D5 | 手册回写：H1/H2/H3/H4/H5/H7 六条要不要我同步修订微信目录里的手册源文件 | 等你发话（手册非仓库内文件，不擅自改） |

---

## 附：本次核对的取证方式

| 结论 | 取证 |
|---|---|
| Dubbo 缺 nacos-client | 解包 `dubbo-3.3.0.jar` 统计 `com/alibaba/nacos/*` = 0、`org/apache/dubbo/registry/nacos/*` = 24；读 `dubbo-3.3.0.pom` 见 `dubbo-registry-nacos optional=true`；读 `META-INF/dubbo/internal/...RegistryFactory` 见 `nacos=NacosRegistryFactory` |
| knife4j 锁 springdoc 2.3.0 | 读 m2 中 `knife4j-4.4.0.pom` / `knife4j-4.5.0.pom` 的 `<knife4j-springdoc-openapi-jakarta.version>`，两版均为 2.3.0 |
| starter 拉 Boot 2.7.18 | 读 `dubbo-spring-boot-starter-3.3.0.pom` 直系依赖 |
| nacos-client 可用版本 | 列 `~/.m2/repository/com/alibaba/nacos/nacos-client/` = 2.0.4 / 2.3.2 / 2.4.2 |
| Nacos 端口与版本兼容 | Nacos 官方文档与 FAQ（2.X 服务端兼容所有 2.X 客户端；9848 客户端 gRPC / 9849 服务端 gRPC） |
| 旧仓 yml 逻辑删除配置失效 | 旧仓 `application.yml` 第 92–101 行，`global-config` 缩进在 `configuration` 之下 |
| 分支与未提交状态 | `git branch --show-current` = master；`git status` = 6 个 staged + `.idea/` `.workbuddy/` 未跟踪 |
