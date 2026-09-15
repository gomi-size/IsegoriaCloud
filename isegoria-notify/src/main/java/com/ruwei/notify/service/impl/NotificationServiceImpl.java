package com.ruwei.notify.service.impl;


import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ruwei.common.core.ErrorCode;
import com.ruwei.common.core.ThrowUtils;
import com.ruwei.common.mybatis.QueryWrapperUtils;
import com.ruwei.innerservice.InnerUserService;
import com.ruwei.model.dto.NotificationQueryDTO;
import com.ruwei.model.dto.NotifyPushMessage;
import com.ruwei.model.dto.SendNotificationDTO;
import com.ruwei.model.entity.Notification;
import com.ruwei.model.entity.User;
import com.ruwei.model.vo.NotificationVO;
import com.ruwei.model.vo.SenderVO;
import com.ruwei.notify.publisher.LocalNotificationPublisher;
import com.ruwei.notify.mapper.NotificationMapper;
import com.ruwei.notify.service.NotificationService;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
* @author Administrator
* @description 针对表【notification(通知表(历史存储/消息中心真相源))】的数据库操作Service实现
* @createDate 2026-07-30 15:44:44
*/
@Service
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, Notification>
    implements NotificationService {

    @DubboReference
    private InnerUserService innerUserService;

    @Resource
    private LocalNotificationPublisher publisher;

    /**
     * 查看用户有多少未读信息
     * @return
     */
    @Override
    public Long userUnreadCount() {
        long loginId = StpUtil.getLoginIdAsLong();
        return lambdaQuery().eq(Notification::getReceiverId, loginId)
                .eq(Notification::getIsRead, 0)
                .count();
    }

    /**
     * 分页查询当前登录用户的通知列表（按时间降序）。
     */
    @Override
    public IPage<NotificationVO> getAllNotification(NotificationQueryDTO notificationQueryDTO) {
        long loginId = StpUtil.getLoginIdAsLong();
        QueryWrapper<Notification> queryWrapper =
                QueryWrapperUtils.getNotificationQueryWrapper(loginId, notificationQueryDTO);
        Page<Notification> page = this.page(
                new Page<>(notificationQueryDTO.getCurrent(), notificationQueryDTO.getPageSize()),
                queryWrapper);

        // 1) 收集本页所有 senderId，批量查 User，避免逐条 N+1
        List<Long> senderIds = page.getRecords().stream()
                .map(Notification::getSenderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, SenderVO> senderMap;
        if (!senderIds.isEmpty()) {
            senderMap = innerUserService.listByIds(senderIds).stream()
                    .collect(Collectors.toMap(
                            User::getId,
                            u -> {
                                SenderVO s = new SenderVO();
                                s.setId(u.getId());
                                s.setUserId(u.getUserId());
                                s.setNickname(u.getNickname());
                                s.setAvatar(u.getAvatar());
                                s.setLevel(u.getLevel());
                                return s;
                            },
                            (a, b) -> a));
        } else {
            senderMap = Collections.emptyMap();
        }

        // 2) 转 VO 并把发送者信息填进去；convert 会保留 total/current/size 分页元数据。
        //    注意：sender 查不到（已注销/删除）或为 null 时不丢弃通知本体，sender 置 null 由前端兜底展示
        return page.convert(notification -> {
            NotificationVO vo = BeanUtil.copyProperties(notification, NotificationVO.class);
            vo.setSender(senderMap.get(notification.getSenderId()));
            return vo;
        });
    }

    /**
     * 已读
     * @param ids
     */
    @Override
    public void readMessage(List<Long> ids) {
        long loginId = StpUtil.getLoginIdAsLong();
        Long count = lambdaQuery().eq(Notification::getReceiverId, loginId)
                .eq(Notification::getIsRead, 0)
                .count();
        ThrowUtils.throwIf(count<=0, ErrorCode.NOT_FOUND_ERROR,"无已读消息");
        LambdaUpdateWrapper<Notification> lambdaUpdateWrapper =new LambdaUpdateWrapper<>();
        lambdaUpdateWrapper.eq(Notification::getReceiverId,loginId)
                .eq(Notification::getIsRead,0);
        // ids 为 null（不传）或空列表时，不追加 in 条件 → 标记该用户全部未读为已读
        if (ids != null && !ids.isEmpty()){
            lambdaUpdateWrapper.in(Notification::getId,ids);
        }
        lambdaUpdateWrapper.set(Notification::getIsRead,1);
        boolean update = update(lambdaUpdateWrapper);

        ThrowUtils.throwIf(!update,ErrorCode.OPERATION_ERROR,"更新失败");
    }

    /**
     * 通用通知写入并实时推送（幂等：同一 {@code bizKey} 已存在则跳过）。
     *
     * <p>供各业务事件监听器复用：幂等检查 → 落库 {@code notification} → 组装
     * {@code NotifyPushMessage} → {@link LocalNotificationPublisher#push} WS 推送。
     * 并发下同一 bizKey 同时到达时，由 {@code uk_biz} 唯一索引兜底（DuplicateKey 视为幂等跳过）。</p>
     */
    @Override
    public boolean sendNotification(SendNotificationDTO dto) {
        // 幂等：同一 bizKey 已存在则跳过，不重复落库/推送
        Long exists = lambdaQuery().eq(Notification::getBizKey, dto.getBizKey()).count();
        if (exists != null && exists > 0) {
            return false;
        }

        Notification n = new Notification();
        n.setReceiverId(dto.getReceiverId());
        n.setSenderId(dto.getSenderId());
        n.setType(dto.getType());
        n.setTargetType(dto.getTargetType());
        n.setTargetId(dto.getTargetId());
        n.setCommentId(dto.getCommentId());
        n.setContent(dto.getContent());
        n.setBizKey(dto.getBizKey());
        n.setIsRead(0);
        n.setCreatedAt(new Date());
        try {
            save(n);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发重复插入：唯一索引 uk_biz 兜底，视为幂等跳过
            return false;
        }

        // 实时推送
        NotifyPushMessage push = new NotifyPushMessage();
        push.setNotificationId(n.getId());
        push.setReceiverId(dto.getReceiverId());
        push.setType(dto.getType());
        push.setSenderId(dto.getSenderId());
        push.setTargetType(dto.getTargetType());
        push.setTargetId(dto.getTargetId());
        push.setCommentId(dto.getCommentId());
        push.setContent(dto.getContent());
        push.setCreatedAt(n.getCreatedAt().getTime());
        publisher.push(dto.getReceiverId(), push);
        return true;
    }
}




