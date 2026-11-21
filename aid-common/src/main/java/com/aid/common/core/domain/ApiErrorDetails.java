package com.aid.common.core.domain;

import java.util.List;
import lombok.Data;

/** 可公开给调用方的错误定位信息。 */
@Data
public class ApiErrorDetails {
    private String errorCode;
    private String field;
    private String suppliedValue;
    private String reason;
    private List<String> allowedValues;
    private String catalogPath;
    private boolean retryable;
    private Long totalReferences;
    private List<DependencyReference> references;
    /** 当前请求中有权访问且需要补全媒体信息的画布资源编号，保留 64 位精度。 */
    private List<String> resourceIds;

    /** 当前有效的配置依赖。 */
    @Data
    public static class DependencyReference {
        private String type;
        private String id;
        private String code;
        private String name;
        private String version;
        private String purpose;
        private String modelId;
        private String capabilityCode;
        private String reason;
        private String managementPath;
    }
}
