package com.aid.media.provider;

import lombok.Data;
import java.util.Map;

/** 同一音频任务的有序变体、格式副本与附属轨道；附属轨道不自动增加计费数量。 */
@Data
public class AudioOutputItem {
    @com.aid.common.aid.oss.annotation.MediaUrl
    private String url;
    private String mediaType = "AUDIO";
    private String role = "main";
    private Integer variantIndex;
    private String upstreamArtifactId;
    private String format;
    private String contentType;
    private Long sizeBytes;
    private Long durationMs;
    private Long originalDurationMs;
    private Integer sampleRate;
    private Integer channels;
    private Boolean persisted;
    private Map<String, Object> metadata;
}
