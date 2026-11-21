package com.aid.notification.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 管理端站内通知请求。 */
public final class NotificationAdminRequests
{
    private NotificationAdminRequests() { }

    @Data
    public static class ListRequest
    {
        @Min(value = 1, message = "页码不能小于1")
        @NotNull(message = "页码不能为空")
        private Integer pageNum = 1;
        @Min(value = 1, message = "每页数量不能小于1")
        @Max(value = 100, message = "每页数量不能超过100")
        @NotNull(message = "每页数量不能为空")
        private Integer pageSize = 20;
        @Pattern(regexp = "^(DRAFT|SCHEDULED|PUBLISHED|WITHDRAWN)?$", message = "通知状态无效")
        private String status;
        @Size(max = 50, message = "关键词不能超过50字")
        private String keyword;
    }

    @Data
    public static class Detail
    {
        @NotBlank(message = "通知编号不能为空")
        @Pattern(regexp = "^[1-9][0-9]{0,18}$", message = "通知编号无效")
        private String id;
    }

    @Data
    public static class Save
    {
        @Pattern(regexp = "^[1-9][0-9]{0,18}$", message = "通知编号无效")
        private String id;
        @Pattern(regexp = "^[1-9][0-9]{0,18}$", message = "版本号无效")
        private String expectedVersion;
        @NotBlank(message = "通知标题不能为空")
        @Size(max = 100, message = "标题不能超过100字")
        private String title;
        @Size(max = 500, message = "摘要不能超过500字")
        private String summary;
        @NotBlank(message = "通知正文不能为空")
        @Size(max = 100000, message = "正文不能超过100000字")
        private String content;
        @Pattern(regexp = "^(MARKDOWN|PLAIN_TEXT)?$", message = "正文格式无效")
        private String contentFormat;
        @NotBlank(message = "收件类型不能为空")
        @Pattern(regexp = "^(ALL|USERS)$", message = "收件类型无效")
        private String recipientType;
        @Size(max = 5000, message = "收件人不能超过5000个")
        private List<@Pattern(regexp = "^[1-9][0-9]{0,18}$", message = "用户编号无效") String> recipientUserIds = new ArrayList<>();
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai", lenient = OptBoolean.FALSE)
        private Date scheduledTime;
        @NotBlank(message = "请求编号不能为空")
        @Size(max = 64, message = "请求编号不能超过64字")
        private String clientRequestId;
    }

    @Data
    public static class Action
    {
        @NotBlank(message = "通知编号不能为空")
        @Pattern(regexp = "^[1-9][0-9]{0,18}$", message = "通知编号无效")
        private String id;
        @NotBlank(message = "版本号不能为空")
        @Pattern(regexp = "^[1-9][0-9]{0,18}$", message = "版本号无效")
        private String expectedVersion;
        @NotBlank(message = "请求编号不能为空")
        @Size(max = 64, message = "请求编号不能超过64字")
        private String clientRequestId;
    }

    @Data
    public static class Recipients
    {
        @Size(max = 50, message = "关键词不能超过50字")
        private String keyword;
        @Min(value = 1, message = "页码不能小于1")
        @NotNull(message = "页码不能为空")
        private Integer pageNum = 1;
        @Min(value = 1, message = "每页数量不能小于1")
        @Max(value = 100, message = "每页数量不能超过100")
        @NotNull(message = "每页数量不能为空")
        private Integer pageSize = 20;
    }
}
