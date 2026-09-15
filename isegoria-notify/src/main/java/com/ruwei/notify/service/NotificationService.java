package com.ruwei.notify.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ruwei.model.dto.NotificationQueryDTO;
import com.ruwei.model.dto.SendNotificationDTO;
import com.ruwei.model.entity.Notification;
import com.ruwei.model.vo.NotificationVO;

import java.util.List;


/**
* @author Administrator
* @description 针对表【notification(通知表(历史存储/消息中心真相源))】的数据库操作Service
* @createDate 2026-07-30 15:44:44
*/
public interface NotificationService extends IService<Notification> {

    /**
     * 查看登录用户有多少未读信息
     * @return
     */
    Long userUnreadCount();

    /**
     * 分页查询当前登录用户的通知列表（按时间降序）。
     *
     * @param notificationQueryDTO 查询条件（含分页参数 current/pageSize，可选 type 过滤）
     * @return 当前用户的通知 VO 分页（保留 total/current/size）
     */
    IPage<NotificationVO> getAllNotification(NotificationQueryDTO notificationQueryDTO);

    /**
     * 已读
     * @param ids
     */
    void readMessage(List<Long> ids);

    /**
     * 通用通知写入并实时推送（幂等：同一 {@code bizKey} 已存在则跳过，不重复落库/推送）。
     *
     * <p>供各业务事件监听器（关注用户 / 关注板块 / 点赞 / 评论……）复用：
     * 幂等检查 → 落库 {@code notification} → 组装 {@code NotifyPushMessage} → WS 推送，
     * 避免每个监听器重复实现同一套逻辑。调用方负责生成幂等键 {@code bizKey} 与展示文案 {@code content}，
     * 字段含义见 {@link SendNotificationDTO}。</p>
     *
     * @param dto 通知入参（接收者/触发者/类型/关联对象/文案/幂等键）
     * @return 是否真正写入并推送（幂等跳过返回 false）
     */
    boolean sendNotification(SendNotificationDTO dto);
}
