package com.ruwei.user.service.Impl;


import cn.dev33.satoken.secure.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.common.mq.ForumMqConstants;
import com.ruwei.common.sensitive.SensitiveWordFilter;
import com.ruwei.innerservice.InnerFollowService;
import com.ruwei.model.dto.UserEditDTO;
import com.ruwei.model.dto.UserLoginDTO;
import com.ruwei.model.dto.UserRegisterDTO;
import com.ruwei.model.entity.User;
import com.ruwei.model.entity.UserFollow;
import com.ruwei.model.enums.AdminEnum;
import com.ruwei.model.enums.StatusEnum;
import com.ruwei.model.mq.UserProfileMessage;
import com.ruwei.model.vo.UserVO;
import com.ruwei.user.mapper.UserMapper;
import com.ruwei.user.service.UserService;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
* @author Administrator
* @description 针对表【user(用户表)】的数据库操作Service实现
* @createDate 2026-07-22 14:07:39
*/
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService {

    @Resource
    private SensitiveWordFilter sensitiveWordFilter;

    @DubboReference
    private InnerFollowService innerFollowService;

    /**
     * userId 自增计数器（对外编码），基于 Redis 原子自增，保证集群/并发下不重复。
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * userId 计数器在 Redis 中的 key。
     */
    private static final String USER_ID_COUNTER_KEY = "isegoria:user:id:counter";

    /**
     * userId 默认起始基准（库内无数据时从此起步）。
     */
    private static final long USER_ID_BASE = 100000L;

    /**
     * 用户注册
     * @param userRegisterDTO
     * @return
     */
    @Override
    public User userRegister(UserRegisterDTO userRegisterDTO) {
        //1.校验参数
        if (userRegisterDTO.getPassword()==null||userRegisterDTO.getUsername()==null||userRegisterDTO.getCheckPassword()==null){
            ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR,"账号或密码不能为空");
        }
        // 用户名：6~12位，不能全为数字
        String username = userRegisterDTO.getUsername();
        ThrowUtils.throwIf(username.length() < 6 || username.length() > 12,
                ErrorCode.PARAMS_ERROR, "用户名长度必须为6~12位");
        ThrowUtils.throwIf(username.matches("^\\d+$"),
                ErrorCode.PARAMS_ERROR, "用户名不能全为数字");

        // 密码：8~12位，不能全为数字
        String password = userRegisterDTO.getPassword();
        ThrowUtils.throwIf(password.length() < 8 || password.length() > 12,
                ErrorCode.PARAMS_ERROR, "密码长度必须为8~12位");
        ThrowUtils.throwIf(password.matches("^\\d+$"),
                ErrorCode.PARAMS_ERROR, "密码不能全为数字");
        //确认密码
        String checkPassword = userRegisterDTO.getCheckPassword();
        ThrowUtils.throwIf(password.length() < 8 || password.length() > 12,
                ErrorCode.PARAMS_ERROR, "密码长度必须为8~12位");
        ThrowUtils.throwIf(password.matches("^\\d+$"),
                ErrorCode.PARAMS_ERROR, "密码不能全为数字");
        ThrowUtils.throwIf(!checkPassword.equals(password),ErrorCode.PARAMS_ERROR,"两次密码不相等");

        boolean exists = lambdaQuery().eq(User::getUsername, username).exists();
        ThrowUtils.throwIf(exists,ErrorCode.OPERATION_ERROR,"用户名已被注册了");

        //2.加密密码
        String encryptedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
        User user = BeanUtil.copyProperties(userRegisterDTO, User.class);
        user.setPassword(encryptedPassword);

        //3.设置 userId：基于 Redis 原子自增，每次注册 +1（首次以库内最大 userId 起步，避免与历史数据冲突）
        user.setUserId(generateUserId());

        //4.设置nikeName
        String pathName="ISEGORIA";
        String randomStr = RandomUtil.randomString(6);
        String nikeName=pathName+"_"+randomStr;
        user.setNickname(nikeName);

        //4.保存到数据库
        boolean result = save(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR,"注册失败");


        return user;

    }



    /**
     * 用户登录
     * @param userLoginDTO
     * @return
     */
    @Override
    public User userLogin(UserLoginDTO userLoginDTO) {
        //1.校验参数
        if (userLoginDTO.getPassword()==null||userLoginDTO.getUsername()==null){
            ThrowUtils.throwIf(true,ErrorCode.PARAMS_ERROR,"账号或密码不能为空");
        }
        // 用户名：6~12位，不能全为数字
        String username = userLoginDTO.getUsername();
        ThrowUtils.throwIf(username.length() < 6 || username.length() > 12,
                ErrorCode.PARAMS_ERROR, "用户名长度必须为6~12位");
        ThrowUtils.throwIf(username.matches("^\\d+$"),
                ErrorCode.PARAMS_ERROR, "用户名不能全为数字");

        // 密码：8~12位，不能全为数字
        String password = userLoginDTO.getPassword();
        ThrowUtils.throwIf(password.length() < 8 || password.length() > 12,
                ErrorCode.PARAMS_ERROR, "密码长度必须为8~12位");
        ThrowUtils.throwIf(password.matches("^\\d+$"),
                ErrorCode.PARAMS_ERROR, "密码不能全为数字");

        //2.检查用户是否存在
        LambdaQueryWrapper<User> lambdaQueryWrapper =new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(User::getUsername,username);
        User user = baseMapper.selectOne(lambdaQueryWrapper);
        ThrowUtils.throwIf(user==null,ErrorCode.NOT_FOUND_ERROR,"用户不存在");

        //3.校验密码：用 checkpw 比对明文与库中 BCrypt 哈希（哈希串内含盐，自动取盐校验，禁止再 gensalt 重哈希）
        String userPassword = user.getPassword();
        ThrowUtils.throwIf(!BCrypt.checkpw(password, userPassword),ErrorCode.PARAMS_ERROR,"密码错误");

        //4.检查账号状态
        Integer status = user.getStatus();
        StatusEnum statusEnum = StatusEnum.getByCode(status);
        ThrowUtils.throwIf(!statusEnum.equals(StatusEnum.NORMAL),ErrorCode.USER_ERROR,"账号异常无法登录，请联系管理员");

        return user;
    }

    /**
     * 判断当前用户是不是管理员
     * @return
     */
    @Override
    public Boolean isAdmin(Long loginId) {
        // 先确认已登录
        StpUtil.checkLogin();
        //获取到当前的用户的id
        Long userId = StpUtil.getLoginIdAsLong();
        Integer status = getById(userId).getAdmin();
        return AdminEnum.getByCode(status).equals(AdminEnum.Admin);
    }

    /**
     * 管理员修改用户状态（禁用 / 启用 / 注销）
     */
    @Override
    public boolean updateUserStatus(Long userId, Integer status) {
        ThrowUtils.throwIf(userId == null || status == null || !StatusEnum.isValid(status),
                ErrorCode.PARAMS_ERROR, "参数不合法");
        User user = this.getById(userId);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        user.setStatus(status);
        return this.updateById(user);
    }

    /**
     * 用户编辑
     * @param userEditDTO
     */
    @Override
    public void editUserInfo(UserEditDTO userEditDTO) {
        ThrowUtils.throwIf(BeanUtil.isEmpty(userEditDTO)||userEditDTO.getId()==null,ErrorCode.PARAMS_ERROR,"参数不能为空");
        long loginId = StpUtil.getLoginIdAsLong();
        if (loginId !=userEditDTO.getId()&&!isAdmin(loginId)){
            ThrowUtils.throwIf(true,ErrorCode.NO_AUTH_ERROR,"无权限,只有本人或者管理员");
        }
       ThrowUtils.throwIf(getById(loginId)==null,ErrorCode.NOT_FOUND_ERROR,"无当前用户");

        // 昵称：长度4-12个字符；不传/为空则不校验也不更新；不允许纯空白
        if(!getById(loginId).getNickname().equals(userEditDTO.getNickname())){
            ThrowUtils.throwIf(StrUtil.isNotBlank(userEditDTO.getNickname()) && !userEditDTO.getNickname().matches("^(?!\\s+$).{4,12}$"),
                    ErrorCode.PARAMS_ERROR, "昵称长度应在4-12个字符之间且不能为纯空白");
        }
        // 生日：格式 yyyy-MM-dd（如 1990-05-20）；不传则不校验
        ThrowUtils.throwIf(userEditDTO.getBirthday() != null && !userEditDTO.getBirthday().toString().matches("^\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])$"),
                ErrorCode.PARAMS_ERROR, "生日格式不正确，应为yyyy-MM-dd");

        // 个性签名：长度1-200个字符；不传/为空则不校验
        ThrowUtils.throwIf(StrUtil.isNotBlank(userEditDTO.getBio()) && !userEditDTO.getBio().matches("^(?!\\s+$).{1,200}$"),
                ErrorCode.PARAMS_ERROR, "个性签名长度应在1-200个字符之间且不能为纯空白");

        // 所在地：长度1-100个字符，仅允许中文、字母、数字及常见地址符号；不传/为空则不校验
        ThrowUtils.throwIf(StrUtil.isNotBlank(userEditDTO.getLocation()) && !userEditDTO.getLocation().matches("^[\\u4e00-\\u9fa5a-zA-Z0-9\\s\\-,.]{1,100}$"),
                ErrorCode.PARAMS_ERROR, "所在地格式不正确，仅允许中英文、数字及常见地址符号，长度1-100");

        // 手机号：中国大陆11位手机号；不传/为空则不校验
        ThrowUtils.throwIf(StrUtil.isNotBlank(userEditDTO.getPhone()) && !userEditDTO.getPhone().matches("^1[3-9]\\d{9}$"),
                ErrorCode.PARAMS_ERROR, "手机号格式不正确");

        // 邮箱：通用邮箱格式校验；不传/为空则不校验
        ThrowUtils.throwIf(StrUtil.isNotBlank(userEditDTO.getEmail()) && !userEditDTO.getEmail().matches("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$"),
                ErrorCode.PARAMS_ERROR, "邮箱格式不正确");

        // ===== 敏感词/违禁词检查（新增）=====
        // 仅对“用户可自由输入的展示字段”做检查，且只在字段非空时执行：
        // 命中任意敏感词（替换/拦截/审核）即由 checkStrict 抛 PARAMS_ERROR 拒绝本次编辑。
        if (StrUtil.isNotBlank(userEditDTO.getNickname())) {
            sensitiveWordFilter.checkStrict(userEditDTO.getNickname(), "昵称");
        }
        if (StrUtil.isNotBlank(userEditDTO.getBio())) {
            sensitiveWordFilter.checkStrict(userEditDTO.getBio(), "个性签名");
        }
        if (StrUtil.isNotBlank(userEditDTO.getLocation())) {
            sensitiveWordFilter.checkStrict(userEditDTO.getLocation(), "所在地");
        }

        User user = BeanUtil.copyProperties(userEditDTO, User.class);
        boolean result = updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新信息失败");

        // 用户资料变更 → 异步重建该作者全部 ES 帖子（昵称/头像冗余在 PostDoc，需重新索引）
        // 注意用被编辑者 id（管理员代改他人资料时也正确）
        rabbitTemplate.convertAndSend(ForumMqConstants.EXCHANGE,
                ForumMqConstants.RK_ES_USER_PROFILE,
                new UserProfileMessage(userEditDTO.getId()));
    }

    /**
     * 用户修改密码
     * @param id
     * @param password
     */
    @Override
    public void editUserPassword(Long id, String password) {
        ThrowUtils.throwIf(id==null||password==null,ErrorCode.PARAMS_ERROR,"参数不能为空");
        long userId = StpUtil.getLoginIdAsLong();
        if (userId!=id){
            ThrowUtils.throwIf(true,ErrorCode.NO_AUTH_ERROR,"无权限,只有本人");
        }
        ThrowUtils.throwIf(getById(userId)==null,ErrorCode.NOT_FOUND_ERROR,"无当前用户");
        // 密码：8~12位，不能全为数字
        ThrowUtils.throwIf(password.length() < 8 || password.length() > 12,
                ErrorCode.PARAMS_ERROR, "密码长度必须为8~12位");
        ThrowUtils.throwIf(password.matches("^\\d+$"),
                ErrorCode.PARAMS_ERROR, "密码不能全为数字");

        String encryptedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
        User user=new User();
        user.setPassword(encryptedPassword);
        user.setId(id);
        boolean result = updateById(user);
        ThrowUtils.throwIf(!result,ErrorCode.OPERATION_ERROR,"修改密码未成功");
    }

    /**
     * 忘记密码
     * @param userId
     * @param password
     */
    @Override
    public void forgetPassword(Long userId,String password) {
        ThrowUtils.throwIf(userId==null|| ObjectUtil.isEmpty(userId),ErrorCode.PARAMS_ERROR,"用户名不能为空");

        boolean exists = lambdaQuery().eq(User::getUserId, userId).exists();
        ThrowUtils.throwIf(!exists,ErrorCode.NOT_FOUND_ERROR,"用户不存在");

        // 密码：8~12位，不能全为数字
        ThrowUtils.throwIf(password.length() < 8 || password.length() > 12,
                ErrorCode.PARAMS_ERROR, "密码长度必须为8~12位");
        ThrowUtils.throwIf(password.matches("^\\d+$"),
                ErrorCode.PARAMS_ERROR, "密码不能全为数字");

        boolean update = lambdaUpdate().eq(User::getUserId, userId)
                .eq(User::getPassword, password)
                .set(User::getPassword, password)
                .update();
        ThrowUtils.throwIf(!update,ErrorCode.OPERATION_ERROR,"修改密码失败");
    }

    /**
     * 当前登录用户获取别人的详情详情（需登录）
     * <p>入参兼容<b>对外编码 userId 与内部主键 id</b>：先按对外编码查询（他人主页常用入口），
     * 查不到时再按内部主键查询（帖子/关注列表等场景前端拿到的作者 id 是内部雪花 id）。
     * 两种 id 数值域不重叠（内部 id 约 19 位、对外编码 10 万起步），不会误匹配。
     * 均查不到时抛「用户不存在」，避免底层 copyProperties 返回 null 后 setter 触发 NPE。</p>
     * @param userId 对方对外编码或内部主键
     */
    @Override
    public UserVO getOtherUserVOInfo(Long userId) {
        ThrowUtils.throwIf(userId == null, ErrorCode.PARAMS_ERROR, "请传入对方 userId 或内部 id");
        User otherUser = lambdaQuery().eq(User::getUserId, userId).one();
        if (BeanUtil.isEmpty(otherUser)) {
            // 兼容内部 id 传参（帖子作者/关注列表里的内部雪花 id）
            otherUser = getById(userId);
        }
        ThrowUtils.throwIf(BeanUtil.isEmpty(otherUser), ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        return buildOtherUserVO(otherUser);
    }

    /**
     * 按内部主键 id 获取他人详情（关注/粉丝列表内部使用）。
     * @param id 对方内部主键
     */
    @Override
    public UserVO getOtherUserVOInfoById(Long id) {
        User otherUser = getById(id);
        ThrowUtils.throwIf(BeanUtil.isEmpty(otherUser), ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        return buildOtherUserVO(otherUser);
    }

    /**
     * 组装他人 VO 并计算当前登录用户与对方的关系标志（关注/粉丝/互关）。
     * 关注关系的 followerId / followeeId 在 user_follow 表中均为内部 id，故此处统一用内部 id 比对。
     * @param otherUser 目标用户实体
     * @return 他人视图 VO
     */
    private UserVO buildOtherUserVO(User otherUser) {
        // 防御：查不到用户（不存在/已删除）时直接抛「用户不存在」，避免后续 copyProperties 返回 null 后 setter 触发 NPE
        ThrowUtils.throwIf(BeanUtil.isEmpty(otherUser), ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        boolean login = StpUtil.isLogin();
        UserVO userVO = BeanUtil.copyProperties(otherUser, UserVO.class);
        userVO.setPhone("***");
        userVO.setEmail("***");
        if(login){
            long loginId = StpUtil.getLoginIdAsLong();
            long otherId = otherUser.getId();

            // 我是否关注了他
            boolean isFollowed = innerFollowService.isFollowing(loginId,otherId);
            userVO.setIsFollowed(isFollowed);
            // 他是否是我的粉丝（即他关注了我）
            LambdaQueryWrapper<UserFollow> heFollowMeWrapper = new LambdaQueryWrapper<>();
            heFollowMeWrapper.eq(UserFollow::getFollowerId, otherId)
                    .eq(UserFollow::getFolloweeId, loginId)
                    .eq(UserFollow::getStatus, 1);
            boolean isFans = innerFollowService.isFollowing(otherId,loginId);
            userVO.setIsFans(isFans);

            // 互相关注 = 我关注他 且 他关注我
            userVO.setIsMutual(isFollowed && isFans);
        }
        return userVO;
    }

    /**
     * 基于 Redis 原子自增生成对外编码 userId。
     * <p>首次（Redis 计数器不存在）以「库内已有最大 userId」与默认基准 {@link #USER_ID_BASE} 取大作为起点，
     * 之后每次 {@code INCR} 保证全局唯一、单调递增；setIfAbsent 仅初始化一次，不会覆盖已自增的值。</p>
     *
     * @return 全新的 userId
     */
    private Long generateUserId() {
        // 仅当计数器尚未初始化时，才以库内最大 userId 为基准写入，避免覆盖已自增的计数
        if (!stringRedisTemplate.hasKey(USER_ID_COUNTER_KEY)) {
            //查询出最大的id
            List<Object> objects = baseMapper.selectObjs(new QueryWrapper<User>().select("max(userId)"));
            Object maxObj = objects.get(0);
            long dbMax = Objects.nonNull(maxObj) ? ((Number) maxObj).longValue() : 0L;

            long base = Math.max(dbMax, USER_ID_BASE);

            stringRedisTemplate.opsForValue().setIfAbsent(USER_ID_COUNTER_KEY, String.valueOf(base));
        }
        // 原子自增，返回自增后的值（首次为 base + 1）
        return stringRedisTemplate.opsForValue().increment(USER_ID_COUNTER_KEY);
    }
}