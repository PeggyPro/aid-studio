package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import com.aid.common.constant.HttpConstants;

/** 厂商鉴权值规范化，避免 Bearer 前缀缺空格或被重复填写。 */
public final class ProviderAuthSupport {

    private ProviderAuthSupport() {
    }

    public static String buildBearerValue(String configuredPrefix, String apiKey) {
        String key = StrUtil.trimToEmpty(apiKey);
        String prefix = configuredPrefix == null
                ? HttpConstants.AUTH_BEARER_PREFIX : configuredPrefix;
        if ("Bearer".equalsIgnoreCase(prefix.trim())) {
            if (key.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
                return key;
            }
            return "Bearer " + key;
        }
        return prefix + key;
    }
}
