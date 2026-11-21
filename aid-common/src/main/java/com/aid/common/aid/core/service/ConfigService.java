package com.aid.common.aid.core.service;


import java.util.List;
import java.util.Map;

/**
 * 通用 参数配置服务
 */
public interface ConfigService {

    /**
     * 根据配置类型和配置key获取值
     *
     * @param category
     * @param configKey
     * @return
     */
    String getConfigValue(String category, String configKey);


    /**
     * 根据分类获取值
     *
     * @param category
     * @return
     */
    Map<String, String> getConfigValues(String category);

    /** 仅在管理员明确同步文件存储配置后记录生效快照；不用于普通配置保存。 */
    void recordStorageSynchronization(String fingerprint);
}
