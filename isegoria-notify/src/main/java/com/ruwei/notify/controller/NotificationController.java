package com.ruwei.notify.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.metadata.IPage;

import com.ruwei.common.core.BaseResponse;
import com.ruwei.common.core.ResultUtils;
import com.ruwei.common.web.RateLimit;
import com.ruwei.model.dto.NotificationQueryDTO;
import com.ruwei.model.vo.NotificationVO;
import com.ruwei.notify.service.NotificationService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/notifications")
@SaCheckLogin
public class NotificationController {

    @Resource
    private NotificationService notificationService;

    /**
     * 查看登录用户有多少未读（红点）
     * 当用户成功后系统自动调用
     */
    @GetMapping("/unReadCount")
    public BaseResponse<Long> userUnreadCount(){
       Long count= notificationService.userUnreadCount();
       return ResultUtils.success(count);
    }

    /**
     * 查看所有信息
     */
    @PostMapping("/getAllNf")
    public BaseResponse<IPage<NotificationVO>> getAllNotification(@RequestBody NotificationQueryDTO notificationQueryDTO){
        return ResultUtils.success(notificationService.getAllNotification(notificationQueryDTO));
    }

    /**
     * 标记已读，若不传递信息id则全部已读。
     * 入参为表单/url 参数（非 JSON）：ids=1&ids=2&ids=3；不传则全部已读。
     */
    @PostMapping("/read")
    @RateLimit(limit = 30, window = 60, prefix = "notificationRead")
    public BaseResponse<String> readMessage(@RequestParam(required = false) List<Long> ids){

        notificationService.readMessage(ids);

        return ResultUtils.success("已读成功");
    }
}