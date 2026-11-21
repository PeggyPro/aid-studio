package com.aid.media.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data
public class MediaTaskAnomalyConfigRequest {
    @NotNull
    private Boolean enabled;
    @NotNull
    @Min(60)
    @Max(604800)
    private Integer anomalyThresholdSeconds;
    @NotNull
    @Min(60)
    @Max(86400)
    private Integer autoRecheckIntervalSeconds;
    @NotNull
    @Min(10)
    @Max(3600)
    private Integer adminRefreshSeconds;
}
