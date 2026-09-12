package com.ruwei.common.mybatis;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ruwei.common.core.BusinessException;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.model.dto.AuditlogQueryDTO;
import com.ruwei.model.dto.BoardQueryDTO;
import com.ruwei.model.dto.NotificationQueryDTO;
import com.ruwei.model.dto.PostQueryDTO;
import com.ruwei.model.dto.UserQueryDTO;
import com.ruwei.model.entity.Auditlog;
import com.ruwei.model.entity.Board;
import com.ruwei.model.entity.BoardFollow;
import com.ruwei.model.entity.Notification;
import com.ruwei.model.entity.Post;
import com.ruwei.model.entity.User;
import com.ruwei.model.entity.UserFollow;
import com.ruwei.model.enums.PostStatusEnum;
import com.ruwei.model.enums.PostVisibilityEnum;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * MyBatis-Plus {@link QueryWrapper} 条件构造工具（六服务共享）。
 *
 * <p>把各业务 DTO 的分页查询条件统一收敛为 QueryWrapper，避免 Service 层重复拼条件。
 * 所有排序字段都经过<b>白名单</b>校验，防止前端传入任意列名造成 orderBy 注入。</p>
 *
 * <p><b>归属说明</b>：本类原在旧单体的 domain/utils 包。微服务拆分时上移到
 * {@code isegoria-common}，依赖方向为 {@code common → model}（仅依赖 entity/dto/enums 与
 * common.core 的异常类型，不依赖任何 Service 接口），因此六个服务可共享同一份实现。
 * 前置调整：分页基类 {@code PageRequest} 已从 common 下沉至 {@code com.ruwei.model.dto}，
 * 使 model 无需反向依赖 common。</p>
 *
 * @author ruwei
 */
public class QueryWrapperUtils {

    /**
     * 允许参与排序的字段白名单（防止 orderBy 注入任意列名）。
     * 与 user 表驼峰列名保持一致。
     */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "userId", "createdAt", "updatedAt",
            "fansCount", "followCount", "postCount", "level", "exp", "status"
    );

    /**
     * 允许参与排序的字段白名单（防止 orderBy 注入任意列名）。
     * 与 board 表驼峰列名保持一致。
     */
    private static final Set<String> BOARD_ALLOWED_SORT_FIELDS = Set.of(
            "id", "name", "slug", "followCount", "postCount", "createdAt", "updatedAt", "status"
    );

    /**
     * 允许参与排序的字段白名单（防止 orderBy 注入任意列名）。
     * 与 post 表驼峰列名保持一致。
     */
    private static final Set<String> POST_ALLOWED_SORT_FIELDS = Set.of(
            "id", "postCode", "boardId", "userId", "title", "createdAt", "updatedAt",
            "likeCount", "commentCount", "collectCount", "viewCount", "shareCount", "score", "status"
    );

    /**
     * 允许参与排序的字段白名单（防止 orderBy 注入任意列名）。
     * 与 auditLog 表驼峰列名保持一致。
     */
    private static final Set<String> AUDITLOG_ALLOWED_SORT_FIELDS = Set.of(
            "id", "adminId", "targetType", "targetId", "action", "createdAt"
    );

    /**
     * 根据查询条件构造用户表的 QueryWrapper。
     *
     * <p>精确匹配：内部 {@code id}、对外 {@code userId}；模糊匹配：{@code username}、{@code nickname}。</p>
     *
     * @param userQueryDTO 查询条件，为 null 时抛参数异常
     * @return 已拼好条件的 QueryWrapper
     */
    public static QueryWrapper<User> getUserQueryWrapper(UserQueryDTO userQueryDTO) {
        if (userQueryDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryDTO.getId();
        Long userId = userQueryDTO.getUserId();
        String username = userQueryDTO.getUsername();
        String nickname = userQueryDTO.getNickname();
        String sortField = userQueryDTO.getSortField();
        String sortOrder = userQueryDTO.getSortOrder();

        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        // 精确匹配（列名与 user 表驼峰列一致）
        queryWrapper.eq(ObjUtil.isNotNull(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotNull(userId), "userId", userId);
        // 模糊匹配
        queryWrapper.like(StrUtil.isNotBlank(username), "username", username);
        queryWrapper.like(StrUtil.isNotBlank(nickname), "nickname", nickname);

        // 排序：常量在前比较，避免 sortOrder 为 null 时空指针；列名走白名单防注入
        boolean isAsc = "ascend".equals(sortOrder);
        boolean canSort = StrUtil.isNotBlank(sortField) && ALLOWED_SORT_FIELDS.contains(sortField);
        queryWrapper.orderBy(canSort, isAsc, sortField);

        return queryWrapper;
    }

    /**
     * 「我关注的人」关系查询条件，用于分页 {@code userFollow} 表。
     *
     * <p>条件：{@code followerId = 当前用户内部 id} 且 {@code status = 1}（关注中），按 {@code createdAt} 倒序。</p>
     *
     * @param followerId 主动关注方内部 id（即 Sa-Token loginId）
     * @return 已拼好条件的 QueryWrapper（作用在 userFollow 表）
     */
    public static QueryWrapper<UserFollow> getFollowingQueryWrapper(Long followerId) {
        if (followerId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "followerId 不能为空");
        }
        QueryWrapper<UserFollow> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("followerId", followerId)
                .eq("status", 1)
                .orderByDesc("createdAt");
        return queryWrapper;
    }

    /**
     * 「我的粉丝」关系查询条件，用于分页 {@code userFollow} 表。
     *
     * <p>条件：{@code followeeId = 当前用户内部 id} 且 {@code status = 1}（关注中），按 {@code createdAt} 倒序。</p>
     *
     * @param followeeId 被关注方内部 id（即 Sa-Token loginId）
     * @return 已拼好条件的 QueryWrapper（作用在 userFollow 表）
     */
    public static QueryWrapper<UserFollow> getFansQueryWrapper(Long followeeId) {
        if (followeeId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "followeeId 不能为空");
        }
        QueryWrapper<UserFollow> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("followeeId", followeeId)
                .eq("status", 1)
                .orderByDesc("createdAt");
        return queryWrapper;
    }

    /**
     * 根据内部 id 集合查询用户，可叠加 DTO 里的 username / nickname 模糊搜索与排序。
     *
     * <p>用于「关注列表 / 粉丝列表」这类先查关系表拿到对端 id 集合、再批量回表取用户信息的场景。
     * 传入空集合时返回永假条件（{@code 1 = 0}），避免生成非法 SQL {@code IN ()} 或误查全表。</p>
     *
     * @param userQueryDTO 查询条件（提供 username / nickname / sortField / sortOrder）
     * @param ids          目标用户内部 id 集合（来自关系表的对端 id）
     * @return 已拼好 {@code in("id", ids)} 及模糊/排序条件的 QueryWrapper
     */
    public static QueryWrapper<User> getUserInIdsQueryWrapper(UserQueryDTO userQueryDTO, List<Long> ids) {
        if (userQueryDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        // id 集合为空时直接给一个永假条件，避免 IN () 非法 SQL 或误查全表
        if (ids == null || ids.isEmpty()) {
            queryWrapper.eq("1", "0");
            return queryWrapper;
        }
        String username = userQueryDTO.getUsername();
        String nickname = userQueryDTO.getNickname();
        String sortField = userQueryDTO.getSortField();
        String sortOrder = userQueryDTO.getSortOrder();

        queryWrapper.in("id", ids);
        queryWrapper.like(StrUtil.isNotBlank(username), "username", username);
        queryWrapper.like(StrUtil.isNotBlank(nickname), "nickname", nickname);

        boolean isAsc = "ascend".equals(sortOrder);
        boolean canSort = StrUtil.isNotBlank(sortField) && ALLOWED_SORT_FIELDS.contains(sortField);
        queryWrapper.orderBy(canSort, isAsc, sortField);
        return queryWrapper;
    }

    /**
     * 通知（消息中心）分页查询条件。
     *
     * <p>条件：{@code receiverId = 当前用户内部 id}（仅查自己的通知），可选按 {@code type} 精确过滤；
     * 固定按 {@code createdAt} 时间<b>降序</b>（最新通知排在最前）。</p>
     *
     * @param receiverId 接收者内部 id（即当前登录用户 Sa-Token loginId）
     * @param dto        查询条件（current / pageSize / type）
     * @return 已拼好条件的 QueryWrapper（作用在 notification 表）
     */
    public static QueryWrapper<Notification> getNotificationQueryWrapper(Long receiverId, NotificationQueryDTO dto) {
        if (dto == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        if (receiverId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "receiverId 不能为空");
        }
        QueryWrapper<Notification> queryWrapper = new QueryWrapper<>();
        // 只查当前用户的通知
        queryWrapper.eq("receiverId", receiverId);
        // 按类型过滤：type 为 null 或 0 时不过滤，查全部类型（避免 Integer 拆箱 NPE）
        Integer type = dto.getType();
        queryWrapper.eq(type != null && type != 0, "type", type);
        // 按时间降序，最新在前
        queryWrapper.orderByDesc("createdAt");
        return queryWrapper;
    }

    /**
     * 根据查询条件构造板块表的 QueryWrapper。
     *
     * <p>模糊匹配：{@code name}；精确匹配：{@code slug} / {@code creatorId}；
     * 可选按 {@code sortField} / {@code sortOrder} 排序；默认按 {@code followCount DESC}（关注数倒序）。</p>
     *
     * @param boardQueryDTO 查询条件，为 null 时抛参数异常
     * @return 已拼好条件的 QueryWrapper（作用在 board 表）
     */
    public static QueryWrapper<Board> getBoardQueryWrapper(BoardQueryDTO boardQueryDTO) {
        if (boardQueryDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        String name = boardQueryDTO.getName();
        String slug = boardQueryDTO.getSlug();
        String description = boardQueryDTO.getDescription();
        Long creatorId = boardQueryDTO.getCreatorId();
        String sortField = boardQueryDTO.getSortField();
        String sortOrder = boardQueryDTO.getSortOrder();

        QueryWrapper<Board> queryWrapper = new QueryWrapper<>();
        // 模糊匹配
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(description), "description", description);
        queryWrapper.like(ObjUtil.isNotNull(slug), "slug", slug);
        queryWrapper.like(ObjUtil.isNotNull(creatorId), "creatorId", creatorId);

        // 排序：常量在前比较，避免 sortOrder 为 null 时空指针；列名走白名单防注入
        boolean isAsc = "ascend".equals(sortOrder);
        boolean canSort = StrUtil.isNotBlank(sortField) && BOARD_ALLOWED_SORT_FIELDS.contains(sortField);
        if (canSort) {
            queryWrapper.orderBy(true, isAsc, sortField);
        } else {
            // 默认按关注数倒序（热门板块优先）
            queryWrapper.orderByDesc("followCount");
        }
        return queryWrapper;
    }

    /**
     * 「我关注的板块」关系查询条件，用于分页 {@code board_follow} 表。
     *
     * <p>条件：{@code userId = 当前用户内部 id}（本表 userId 统一存内部主键，与 user_follow 约定一致）
     * 且 {@code status = 1}（仅关注中，排除已取消的记录），按 {@code createdAt} 倒序。</p>
     *
     * @param userId 关注者内部 id（即 Sa-Token loginId）
     * @return 已拼好条件的 QueryWrapper（作用在 board_follow 表）
     */
    public static QueryWrapper<BoardFollow> getBoardFollowQueryWrapper(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不能为空");
        }
        QueryWrapper<BoardFollow> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userId", userId)
                .eq("status", 1)
                .orderByDesc("createdAt");
        return queryWrapper;
    }

    /**
     * 「我创建的板块的粉丝」关系查询条件，用于分页 {@code board_follow} 表。
     *
     * <p>条件：{@code boardId IN (我创建的板块 id 集合)} 且 {@code status = 1}（仅关注中），
     * 按 {@code createdAt} 倒序。入参为空集合时抛参数异常（由调用方保证「未创建板块则直接返回空页」）。</p>
     *
     * @param boardIds 我创建的板块内部 id 集合
     * @return 已拼好条件的 QueryWrapper（作用在 board_follow 表）
     */
    public static QueryWrapper<BoardFollow> getBoardFansQueryWrapper(Collection<Long> boardIds) {
        if (boardIds == null || boardIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "boardIds 不能为空");
        }
        QueryWrapper<BoardFollow> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("boardId", boardIds)
                .eq("status", 1)
                .orderByDesc("createdAt");
        return queryWrapper;
    }

    /**
     * 根据查询条件构造帖子表的 QueryWrapper。
     *
     * <p>精确匹配：{@code id} / {@code boardId} / {@code userId}；模糊匹配：{@code postCode} / {@code title} /
     * {@code createdAt}（datetime 列 LIKE，传 "2026-08-05" 可查当天）。
     * <b>可见性/状态过滤</b>：{@code visibility}（公开/仅粉丝可见/私密）与 {@code status}
     * （已发布/草稿/审核中/下架）传<b>中文文字</b>，经对应枚举转整数后精确匹配；<b>为空则查询所有</b>，
     * 非法文字抛参数错误。其余条件均不传则查询全部。
     * 排序走 {@link #POST_ALLOWED_SORT_FIELDS} 白名单；未传排序字段时默认按 {@code createdAt} 倒序（最新在前）；
     * <b>主页场景</b>（按 {@code userId} 查询）未传排序字段时改为「置顶优先」：{@code isTop DESC, createdAt DESC}，
     * 使置顶仅在个人/他人主页查看帖子时体现，首页信息流不体现。</p>
     *
     * <p><b>注意</b>：本方法只按入参条件过滤，不做「默认可见性」兜底（status=已发布 的追加由
     * 调用方 PostService 根据「是否查本人」决定）；status 条件与调用方追加条件为 AND 叠加
     * （如查他人时传 status=审核中 会与已发布条件互斥返回空，符合可见性语义）。</p>
     *
     * @param postQueryDTO 查询条件，为 null 时抛参数异常
     * @return 已拼好条件的 QueryWrapper
     */
    public static QueryWrapper<Post> getPostQueryWrapper(PostQueryDTO postQueryDTO) {
        if (postQueryDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = postQueryDTO.getPostId();
        String postCode = postQueryDTO.getPostCode();
        Long boardId = postQueryDTO.getBoardId();
        String title = postQueryDTO.getTitle();
        Long userId = postQueryDTO.getUserId();
        String createdAt = postQueryDTO.getCreatedAt();
        String visibilityText = postQueryDTO.getVisibility();
        String statusText = postQueryDTO.getStatus();
        String sortField = postQueryDTO.getSortField();
        String sortOrder = postQueryDTO.getSortOrder();

        QueryWrapper<Post> queryWrapper = new QueryWrapper<>();
        // 精确匹配（列名与 post 表驼峰列一致）
        queryWrapper.eq(ObjUtil.isNotNull(id) && id != 0, "id", id);
        queryWrapper.eq(ObjUtil.isNotNull(boardId) && boardId != 0, "boardId", boardId);
        queryWrapper.eq(ObjUtil.isNotNull(userId) && userId != 0, "userId", userId);
        // 模糊匹配
        queryWrapper.like(StrUtil.isNotBlank(postCode), "postCode", postCode);
        queryWrapper.like(StrUtil.isNotBlank(title), "title", title);
        queryWrapper.like(StrUtil.isNotBlank(createdAt), "createdAt", createdAt);

        // 可见性 / 生命周期状态：中文文字 → 枚举转整数精确匹配；为空则查询所有
        // visibilityList 非空时优先按 IN 匹配（如「公开 或 仅粉丝可见」），忽略单值 visibility
        List<String> visibilityList = postQueryDTO.getVisibilityList();
        if (CollUtil.isNotEmpty(visibilityList)) {
            List<Integer> visibilityCodes = visibilityList.stream()
                    .map(PostVisibilityEnum::codeOfText)
                    .filter(Objects::nonNull)
                    .toList();
            if (visibilityCodes.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "非法的可见性列表：" + visibilityList);
            }
            queryWrapper.in("visibility", visibilityCodes);
        } else if (StrUtil.isNotBlank(visibilityText)) {
            Integer visibilityCode = PostVisibilityEnum.codeOfText(visibilityText);
            if (visibilityCode == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "非法的可见性：" + visibilityText);
            }
            queryWrapper.eq("visibility", visibilityCode);
        }
        if (StrUtil.isNotBlank(statusText)) {
            Integer statusCode = PostStatusEnum.codeOfText(statusText);
            if (statusCode == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "非法的状态：" + statusText);
            }
            queryWrapper.eq("status", statusCode);
        }

        // 排序：常量在前比较，避免 sortOrder 为 null 时空指针；列名走白名单防注入
        boolean isAsc = "ascend".equals(sortOrder);
        boolean canSort = StrUtil.isNotBlank(sortField) && POST_ALLOWED_SORT_FIELDS.contains(sortField);
        // 主页场景：仅当按 userId 查询（点进自己/他人主页看帖子）时，置顶帖优先展示；
        // 首页信息流（不传 userId）/ 关注流不体现置顶
        boolean profileView = ObjUtil.isNotNull(userId) && userId != 0;
        if (canSort) {
            queryWrapper.orderBy(true, isAsc, sortField);
        } else if (profileView) {
            // 主页：置顶帖在前，再按创建时间倒序（最新在前）
            queryWrapper.orderByDesc("isTop").orderByDesc("createdAt");
        } else {
            // 默认按创建时间倒序（最新在前）
            queryWrapper.orderByDesc("createdAt");
        }
        return queryWrapper;
    }

    /**
     * 根据查询条件构造审核日志表的 QueryWrapper。
     *
     * <p>精确匹配：{@code id} / {@code adminId} / {@code targetType} / {@code targetId} / {@code action}；
     * 模糊匹配：{@code remark} / {@code createdAt}（datetime 列 LIKE，传 "2026-08-05" 可查当天）。
     * 排序走 {@link #AUDITLOG_ALLOWED_SORT_FIELDS} 白名单；未传排序字段时默认按 {@code createdAt} 倒序（最新在前）。</p>
     *
     * @param auditlogQueryDTO 查询条件，为 null 时抛参数异常
     * @return 已拼好条件的 QueryWrapper（作用在 auditLog 表）
     */
    public static QueryWrapper<Auditlog> getAuditlogQueryWrapper(AuditlogQueryDTO auditlogQueryDTO) {
        if (auditlogQueryDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = auditlogQueryDTO.getId();
        Long adminId = auditlogQueryDTO.getAdminId();
        Integer targetType = auditlogQueryDTO.getTargetType();
        Long targetId = auditlogQueryDTO.getTargetId();
        Integer action = auditlogQueryDTO.getAction();
        String remark = auditlogQueryDTO.getRemark();
        String createdAt = auditlogQueryDTO.getCreatedAt();
        String sortField = auditlogQueryDTO.getSortField();
        String sortOrder = auditlogQueryDTO.getSortOrder();

        QueryWrapper<Auditlog> queryWrapper = new QueryWrapper<>();
        // 精确匹配（列名与 auditLog 表驼峰列一致）
        queryWrapper.eq(ObjUtil.isNotNull(id) && id != 0, "id", id);
        queryWrapper.eq(ObjUtil.isNotNull(adminId) && adminId != 0, "adminId", adminId);
        queryWrapper.eq(ObjUtil.isNotNull(targetType), "targetType", targetType);
        queryWrapper.eq(ObjUtil.isNotNull(targetId) && targetId != 0, "targetId", targetId);
        queryWrapper.eq(ObjUtil.isNotNull(action), "action", action);
        // 模糊匹配
        queryWrapper.like(StrUtil.isNotBlank(remark), "remark", remark);
        queryWrapper.like(StrUtil.isNotBlank(createdAt), "createdAt", createdAt);

        // 排序：常量在前比较，避免 sortOrder 为 null 时空指针；列名走白名单防注入
        boolean isAsc = "ascend".equals(sortOrder);
        boolean canSort = StrUtil.isNotBlank(sortField) && AUDITLOG_ALLOWED_SORT_FIELDS.contains(sortField);
        if (canSort) {
            queryWrapper.orderBy(true, isAsc, sortField);
        } else {
            // 默认按创建时间倒序（最新在前）
            queryWrapper.orderByDesc("createdAt");
        }
        return queryWrapper;
    }
}
