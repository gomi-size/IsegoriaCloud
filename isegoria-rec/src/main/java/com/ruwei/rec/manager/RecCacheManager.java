package com.ruwei.rec.manager;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.NumberUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RecCacheManager {
    private static final String EXPOSURE = "feed:exposure:";        // ZSet
    private static final String INTEREST = "uinterest:";            // String INCR
    private static final Duration EXP_TTL = Duration.ofDays(4);
    private static final Duration INT_TTL = Duration.ofDays(2);
    private static final String SENTINEL = "-1";


    @Resource
    private StringRedisTemplate redis;

    //将以及曝光的数据写入redis
    public void addExposures(Long userId, Collection<Long> postList){
        if(postList.isEmpty()) return;
        String key=EXPOSURE+userId;

        //  核心写入逻辑：将帖子ID和当前时间戳写入 Redis 的 ZSet（有序集合）中
        Set<ZSetOperations.TypedTuple<String>> tuples = postList.stream()
                .map(id -> ZSetOperations.TypedTuple.of(String.valueOf(id), (double) System.currentTimeMillis()))
                .collect(Collectors.toSet());
        redis.opsForZSet().add(key, tuples);
        redis.expire(key,EXP_TTL);
    }

    /**
     * 批量剔除已曝光：返回入参中<b>未被曝光过</b>的 postId（pipeline 批量 zScore，每个 O(1)）。
     *
     * <p><b>键缺失 = 无曝光记录 = 全放行</b>（不建哨兵：与 FollowCacheManager 不同，
     * 这里是"无记录=没看过"语义，空曝光集合建哨兵反而破坏全放行）。</p>
     *
     * @param userId   用户内部 id
     * @param postList 待过滤的帖子内部 id 集合
     * @return 未曝光过的帖子 id（保序），已曝光过的被剔除
     */
    public Set<Long> filterExposed(Long userId, Collection<Long> postList) {
        if (postList == null || postList.isEmpty()) {
            return new LinkedHashSet<>();
        }

        String key = EXPOSURE + userId;

        // 键缺失 = 无曝光记录 = 全放行
        if (!redis.hasKey(key)) {
            return new LinkedHashSet<>(postList);
        }

        // 转 List 以便按索引对齐 pipeline 结果
        List<Long> ids = new ArrayList<>(postList);

        // 修复警告1：处理序列化可能返回 null 的警告 (Nullability)
        // 建议直接获取 String 序列化器，避免每次都在循环里 get
        RedisSerializer<String> stringSerializer = redis.getStringSerializer();
        byte[] rawKey = Objects.requireNonNull(stringSerializer.serialize(key), "Key 序列化结果不能为null");

        // 批量 zScore：结果列表元素为 null 表示 Redis 中不存在（即未曝光过）
        List<Object> scores = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Long postId : ids) {
                byte[] rawMember = Objects.requireNonNull(
                        stringSerializer.serialize(String.valueOf(postId)),
                        "Member 序列化结果不能为null"
                );
                connection.zSetCommands().zScore(rawKey, rawMember);
            }
            return null;
        });
        Set<Long> result = new LinkedHashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            if (scores.get(i) == null) {
                result.add(ids.get(i));
            }
        }

        return result;
    }

    /**
     * 短期兴趣累加（String INCR，dim 2标签 3类型 4板块 5作者）。
     *
     * @param userId 用户内部 id
     * @param dim    兴趣维度（2标签 3类型 4板块 5作者）
     * @param value  维度值（tagId / type码 / boardId / authorId）
     * @param delta  增量（强信号 +1、浏览弱信号 +0.2、负反馈 -1）
     */
    public void incrInterest(Long userId,int dim,String value ,double delta){
        String key = INTEREST + userId + ":" + dim + ":" + value;
        redis.opsForValue().increment(key,delta);
        redis.expire(key,INT_TTL);
    }

    /**
     * 取某用户短期兴趣键值对（供 InterestMergeJob 合并），键不存在返回空。
     *
     * @param userId 用户内部 id
     * @return {dim:value -> weight}（如 {"2:1800001": 3.2, "3:1": 1.4}）
     */
    public Map<String, Double> scanShortInterest(Long userId) {
        String matchPattern = INTEREST + userId + ":*";
        String prefix = INTEREST + userId + ":"; // 用于后续截取字符串
        Map<String, Double> resultMap = new HashMap<>();

        // 1. 使用 SCAN 命令安全地获取匹配的 Keys，防止阻塞 Redis
        Set<String> keys = scanKeys(matchPattern);

        if (keys == null || keys.isEmpty()) {
            return resultMap; // 如果没扫描到，直接返回空Map
        }

        // 2. 将 Set 转为 List，使用 multiGet 批量获取所有权重分数（只需 1 次网络 I/O）
        List<String> keyList = new ArrayList<>(keys);
        List<String> values = redis.opsForValue().multiGet(keyList);

        if (values == null) {
            return resultMap;
        }

        // 3. 遍历拼接结果
        for (int i = 0; i < keyList.size(); i++) {
            String fullKey = keyList.get(i);
            String valStr = values.get(i);

            if (valStr != null) {
                try {
                    // 截去前缀 "uinterest:{userId}:" ，提取出 "dim:value"
                    String dimAndValue = fullKey.substring(prefix.length());
                    // 将查出来的字符串权重转成 Double 存入 Map
                    resultMap.put(dimAndValue, Double.parseDouble(valStr));
                } catch (NumberFormatException e) {
                    // 容错处理：如果解析出来的分数不是数字，直接忽略这条脏数据
                }
            }
        }

        return resultMap;
    }

    /**
     * 删除某用户全部短期兴趣键（InterestMergeJob 合并进长期画像后清理，防残留）。
     *
     * @param uid 用户内部 id
     */
    public void deleteShortInterest(Long uid) {
        Set<String> keys = scanKeys(INTEREST + uid + ":*");
        if (CollUtil.isNotEmpty(keys)) {
            redis.delete(keys);
        }
    }
    /**
     * SCAN 按 pattern 拉键（不阻塞 Redis；单用户前缀量小，COUNT 100 足够）。
     */
    private Set<String> scanKeys(String pattern) {
        return redis.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> tmpKeys = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(100)
                    .build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    tmpKeys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                log.warn("SCAN 拉键失败 pattern={}: {}", pattern, e.getMessage());
            }
            return tmpKeys;
        });
    }

    /**
     * 从已曝光的帖子中获取帖子（推荐流「空页回显」兜底数据源：无新内容可推时，
     * 把最近推流过的帖子原样返回给前端，实现不空屏）。
     *
     * <p><b>存储形态提醒：曝光档案是 ZSet</b>（member=帖子内部 id，score=曝光毫秒时间戳），
     * 读取必须走 {@code opsForZSet()}；不能像短期兴趣（uinterest:*，String INCR）那样用
     * {@code opsForValue()}——对 ZSet 键做 String GET 只会拿到 null，是本类易误用点。</p>
     *
     * <p>键缺失 = 从未曝光 = 无可回显 → 返回空列表（不建哨兵，与 {@link #filterExposed} 的
     * 「无记录=没看过」语义一致）。</p>
     *
     * @param userId 用户内部 id（仅登录用户写曝光档案；游客恒空）
     * @param count  最多返回条数（最近曝光优先）；&lt;=0 表示返回全部
     * @return 已曝光的帖子内部 id，按曝光时间倒序（最近推流过的在前）；无记录返回空列表
     */
    public List<Long> get(Long userId, long count) {
        String key = EXPOSURE + userId;
        if (Boolean.FALSE.equals(redis.hasKey(key))) {
            return List.of();
        }
        // ZREVRANGE：score=曝光时间戳，倒序 = 最近推流过的排最前，正好做「刚看过的内容优先回显」
        Set<String> members = count > 0
                ? redis.opsForZSet().reverseRange(key, 0, count - 1)
                : redis.opsForZSet().reverseRange(key, 0, -1);
        if (CollUtil.isEmpty(members)) {
            return List.of();
        }
        // 保序过滤脏数据（member 应为雪花数字串；LinkedHashSet 保 ZREVRANGE 顺序）
        List<Long> result = new ArrayList<>(members.size());
        for (String m : members) {
            if (NumberUtil.isLong(m)) {
                result.add(Long.valueOf(m));
            }
        }
        return result;
    }

    /**
     * 从已经曝光的帖子中获取帖子（取该用户全部已曝光帖，最近曝光在前）。
     *
     * @param userId 用户内部 id
     * @return 全部已曝光的帖子内部 id（最近曝光在前）；无记录返回空列表
     */
    public List<Long> get(Long userId) {
        return get(userId, -1);
    }
















}
