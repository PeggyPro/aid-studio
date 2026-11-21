package com.aid.media.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.Date;
public final class MediaTaskAnomalyRequests {
    private MediaTaskAnomalyRequests() {
    }

    @Data
    public static class TaskRecheck {
        @NotBlank
        @Size(min = 2, max = 500)
        private String reason;
    }

    @Data
    public static class ContinueWait {
        @NotBlank
        @Size(max = 500)
        private String reason;

        @NotNull
        @Future
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private Date nextReviewTime;
    }

    @Data
    public static class ConfirmFailure {
        @AssertTrue(message = "请确认人工失败")
        private boolean confirmed;

        @NotBlank
        @Size(min = 2, max = 500)
        private String reason;
    }
}
