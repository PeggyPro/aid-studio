package com.aid.notification.controller;

import com.aid.common.constant.HttpStatus;
import com.aid.common.core.domain.ApiErrorDetails;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.domain.model.LoginUser;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.notification.dto.NotificationUserRequests;
import com.aid.notification.service.NotificationRealtimeService;
import com.aid.notification.service.NotificationService;
import com.aid.notification.vo.NotificationViews;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C端本人站内系统通知接口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/notification")
public class UserNotificationController
{
    private final NotificationService notificationService;
    private final NotificationRealtimeService realtimeService;

    @PostMapping("/list")
    @Operation(summary = "分页查询本人系统通知", description = "按发布时间和发布序号倒序稳定分页，可按已读状态筛选")
    public AjaxResult list(@Valid @RequestBody NotificationUserRequests.ListRequest request)
    {
        Long userId = requireCUser();
        NotificationViews.Page<NotificationViews.UserNotification> page = notificationService.userList(userId, request);
        return AjaxResult.success(page.getItems())
                .put("total", page.getTotal())
                .put("pageNum", page.getPageNum())
                .put("pageSize", page.getPageSize())
                .put("stateVersion", page.getStateVersion());
    }

    @PostMapping("/detail")
    @Operation(summary = "查询本人系统通知详情", description = "仅返回当前账户可见的已发布通知正文")
    public AjaxResult detail(@Valid @RequestBody NotificationUserRequests.IdRequest request)
    {
        return AjaxResult.success(notificationService.userDetail(requireCUser(), request.getId()));
    }

    @PostMapping("/unread")
    @Operation(summary = "查询本人通知未读状态", description = "在一致性快照中返回unreadCount与stateVersion")
    public AjaxResult unread(@Valid @RequestBody NotificationUserRequests.StateRequest request)
    {
        return AjaxResult.success(notificationService.unread(requireCUser()));
    }

    @PostMapping("/read")
    @Operation(summary = "标记一条本人通知已读", description = "首次阅读惰性写入，重复请求保持幂等")
    public AjaxResult read(@Valid @RequestBody NotificationUserRequests.IdRequest request)
    {
        return AjaxResult.success(notificationService.read(requireCUser(), request.getId()));
    }

    @PostMapping("/read-all")
    @Operation(summary = "标记本人当前通知全读", description = "以严格发布序号建立并发安全水位，不误读并发后发布消息")
    public AjaxResult readAll(@Valid @RequestBody NotificationUserRequests.StateRequest request)
    {
        return AjaxResult.success(notificationService.readAll(requireCUser()));
    }

    @PostMapping("/ticket")
    @Operation(summary = "签发通知WebSocket票据",
            description = "返回短时单次票据，票据须通过Sec-WebSocket-Protocol提供且不得拼入URL")
    public AjaxResult ticket(@Valid @RequestBody NotificationUserRequests.Ticket request)
    {
        Long userId = requireCUser();
        LoginUser loginUser = SecurityUtils.getLoginUser();
        return AjaxResult.success(realtimeService.createTicket(
                userId, loginUser.getToken(), request.getKnownStateVersion()));
    }

    private Long requireCUser()
    {
        Long userId = SecurityUtils.getUserId();
        if (SecurityUtils.isBackendUser() || !notificationService.isActiveCUser(userId))
        {
            ApiErrorDetails detail = new ApiErrorDetails();
            detail.setErrorCode("NOTIFICATION_C_USER_REQUIRED");
            detail.setReason("仅限C端用户");
            throw new ServiceException("无访问权限", HttpStatus.FORBIDDEN).setClientErrorData(detail);
        }
        return userId;
    }
}
