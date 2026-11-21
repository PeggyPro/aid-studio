package com.aid.notification.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** C端站内通知请求。 */
public final class NotificationUserRequests
{
    private NotificationUserRequests() { }

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
        private Boolean isRead;
    }

    @Data
    public static class IdRequest
    {
        @NotBlank(message = "通知编号不能为空")
        @Pattern(regexp = "^[1-9][0-9]{0,18}$", message = "通知编号无效")
        private String id;
    }

    @Data
    public static class StateRequest
    {
    }

    @Data
    public static class Ticket
    {
        @Size(max = 64, message = "状态版本不能超过64字")
        @Pattern(regexp = "^([0-9]+:[0-9]+)?$", message = "状态版本无效")
        private String knownStateVersion;
    }
}
