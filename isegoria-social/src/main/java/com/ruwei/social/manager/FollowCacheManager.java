package com.ruwei.social.manager;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.ruwei.model.entity.UserFollow;
import com.ruwei.social.mapper.UserFollowMapper;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 关注关系 Redis 热索引：uf:following:{id} / uf:followers:{id}
 * 读优先 Redis，键缺失时回源 user_follow 表重建（懒加载，无需迁移脚本）。
 * 写路径由事件监听器在事务提交后调用（见 FollowRedisEventListener）。
 */
@Component
public class FollowCacheManager {

    //设置key
    private static final String PREFIX_FOLLOWING = "uf:following:";
    private static final String PREFIX_FOLLOWERS = "uf:followers:";
    private static final Duration TTL = Duration.ofDays(7);
    /** 空集占位哨兵（防空穿透），所有对外读集合的方法必须过滤 */
    private static final String SENTINEL = "-1";


    @Resource
    private StringRedisTemplate redis;

    @Resource
    private UserFollowMapper userFollowMapper;


    /**
     * 关注问题
     * @param a 关注者
     * @param b 被关注者
     */
    public void addFollowing(Long a, Long b){
        //a关注了b
        redis.opsForSet().add(PREFIX_FOLLOWING+a, String.valueOf(b));
        //b这里需要多一个关注也就是粉丝
        redis.opsForSet().add(PREFIX_FOLLOWERS+b, String.valueOf(a));
        refreshTtl(a,b);
    }

    /**
     * 取关问题
     * @param a 取关者
     * @param b 被取关者
     */
    public void removeFollow(Long a,Long b){

        redis.opsForSet().remove(PREFIX_FOLLOWING+a, String.valueOf(b));
        redis.opsForSet().remove(PREFIX_FOLLOWERS+b, String.valueOf(a));
        refreshTtl(a,b);
    }

    /**
     * a 是否关注了 b
     * @param a 关注者（内部 id）
     * @param b 被关注者（内部 id）
     */
    public Boolean isFollowing(Long a,Long b){
        String key=PREFIX_FOLLOWING+a;
        ensureLoaded(key, a, true);
        return redis.opsForSet().isMember(key,String.valueOf(b));
    }

    /**
     * b 是否关注了 a（等价于 a 的粉丝集合里有没有 b）。
     * 注意与 {@link #isFollowing} 的参数方向相反。
     */
    public Boolean isFollower(Long a,Long b){
        return isFollowing(b,a);
    }

    /** 与 A 互相关注的人（SINTER：A 的关注 ∩ A 的粉丝），已过滤空集哨兵 */
    public Set<String> getMutualIds(Long a) {
        ensureLoaded(PREFIX_FOLLOWING + a, a, true);
        ensureLoaded(PREFIX_FOLLOWERS + a, a, false);
        Set<String> ids = redis.opsForSet().intersect(PREFIX_FOLLOWING + a, PREFIX_FOLLOWERS + a);
        if (ids == null) {
            return Collections.emptySet();
        }
        ids.remove(SENTINEL);
        return ids;
    }

    /**
     * 查看我关注的用户 id 集合（键缺失自动回源重建，已过滤空集哨兵）
     * @param a 当前用户内部 id
     * @return 我关注的用户内部 id 集合
     */
    public Set<String> getFollower(Long a){
        String key = PREFIX_FOLLOWING + a;
        ensureLoaded(key, a, true);
        Set<String> ids = redis.opsForSet().members(key);
        if (ids == null) {
            return Collections.emptySet();
        }
        ids.remove(SENTINEL);
        return ids;
    }

    /**
     * 查看粉丝 id 集合（键缺失自动回源重建，已过滤空集哨兵）
     * @param a 当前用户内部 id
     * @return 粉丝的用户内部 id 集合
     */
    public Set<String> getFollowers(Long a) {
        String key = PREFIX_FOLLOWERS + a;
        ensureLoaded(key, a, false);
        Set<String> ids = redis.opsForSet().members(key);
        if (ids == null) {
            return Collections.emptySet();
        }
        ids.remove(SENTINEL);
        return ids;
    }

    /**
     * 回源重建
     */
    private void ensureLoaded(String key,Long uid,Boolean following){
        //有这个key的话就可以直接返回
        if(redis.hasKey(key)){
            return;
        }
        //没有需要重建
        LambdaQueryWrapper<UserFollow> lambdaQueryWrapper=new LambdaQueryWrapper<>();
        if(following){
            lambdaQueryWrapper.eq(UserFollow::getFollowerId,uid).eq(UserFollow::getStatus,1).select(UserFollow::getFolloweeId);
        }else {
            lambdaQueryWrapper.eq(UserFollow::getFolloweeId,uid).eq(UserFollow::getStatus,1).select(UserFollow::getFollowerId);
        }
        List<UserFollow> userFollows = userFollowMapper.selectList(lambdaQueryWrapper);
        if(CollUtil.isEmpty(userFollows)){
            // 空集也建键并设 TTL，避免每次读都回源打 DB（防空穿透）
            redis.opsForSet().add(key, "-1");
            redis.expire(key, TTL);
            return;
        }
        String[] strings = userFollows.stream().map(userFollow -> String.valueOf(following ? userFollow.getFolloweeId() : userFollow.getFollowerId())).toArray(String[]::new);
        redis.opsForSet().add(key,strings);
        redis.expire(key, TTL);
    }


    private void refreshTtl(Long a, Long b) {
        redis.expire(PREFIX_FOLLOWING + a, TTL);
        redis.expire(PREFIX_FOLLOWERS + b, TTL);
    }



}
