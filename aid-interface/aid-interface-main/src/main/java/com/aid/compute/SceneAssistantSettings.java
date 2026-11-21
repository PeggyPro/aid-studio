package com.aid.compute;

import com.aid.businessscene.BusinessSceneComputePolicyService;
import com.aid.common.exception.ServiceException;
import com.aid.compute.domain.AidComputeNode;
import com.aid.compute.mapper.AidComputeNodeMapper;
import com.aid.businessscene.mapper.AidBusinessEntrypointMapper;
import com.aid.core.domain.SysConfig;
import com.aid.system.mapper.SysConfigMapper;
import com.aid.common.constant.CacheConstants;
import com.aid.common.core.redis.RedisCache;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Service
public class SceneAssistantSettings {
    public static final String SCENE_CODE = "previz_3dbox";
    private static final Set<String> BUILD_ENGINES = Set.of("threejs", "blender");
    private static final String KEY = "aid.compute.scene.previz_3dbox.assistant-enabled";
    private final SysConfigMapper settings;
    private final AidBusinessEntrypointMapper scenes;
    private final RedisCache cache;
    private final AidComputeNodeMapper nodes;
    private final BusinessSceneComputePolicyService pools;
    private final boolean runtimeEnabled;
    private final boolean buildRuntimeConfigured;
    private final ComputeHealthPolicy healthPolicy;

    public SceneAssistantSettings(SysConfigMapper settings, AidBusinessEntrypointMapper scenes, RedisCache cache, AidComputeNodeMapper nodes,
            BusinessSceneComputePolicyService pools,
            @Value("${aid.previz.runtime.enabled:false}") boolean runtimeEnabled,
            @Value("${aid.previz.local-worker.enabled:false}") boolean buildRuntimeConfigured,
            ComputeHealthPolicy healthPolicy) {
        this.settings = settings;
        this.scenes = scenes;
        this.cache = cache;
        this.nodes = nodes;
        this.pools = pools;
        this.runtimeEnabled = runtimeEnabled;
        this.buildRuntimeConfigured = buildRuntimeConfigured;
        this.healthPolicy = healthPolicy;
    }

    public record View(boolean enabled, boolean available, boolean canEnable, String poolCode, String message) { }

    private SysConfig stored() {
        SysConfig condition = new SysConfig();
        condition.setConfigKey(KEY);
        return settings.selectConfig(condition);
    }

    private boolean enabled(SysConfig setting) {
        return setting != null && "true".equals(setting.getConfigValue());
    }

    private boolean automatic(SysConfig setting) {
        return setting != null && "auto".equals(setting.getConfigValue());
    }

    private String automaticPool(String configuredPool) {
        if (!runtimeEnabled || !buildRuntimeConfigured || (configuredPool.isEmpty() && pools.managed(SCENE_CODE))) {
            return "";
        }
        if (!configuredPool.isEmpty()) {
            return eligiblePools(configuredPool, true).isEmpty() ? "" : configuredPool;
        }
        List<String> readyPools = eligiblePools(null, true);
        return readyPools.size() == 1 ? readyPools.get(0) : "";
    }

    public boolean available() {
        if (!runtimeEnabled) return false;
        SysConfig setting = stored();
        boolean useAutomaticPool = automatic(setting);
        if (!useAutomaticPool && !enabled(setting)) return false;
        String pool = pools.configuredPool(SCENE_CODE);
        if (useAutomaticPool) return !automaticPool(pool).isEmpty();
        return !pool.isEmpty() && !eligiblePools(pool, false).isEmpty();
    }

    public String poolForNewBuild() {
        String pool = pools.configuredPool(SCENE_CODE);
        boolean useAutomaticPool = automatic(stored());
        if (useAutomaticPool) pool = automaticPool(pool);
        if (pool.isEmpty() && (useAutomaticPool || pools.managed(SCENE_CODE))) {
            throw new ComputeResourcesUnavailableException();
        }
        return pool;
    }

    public void requireEnabled() {
        if (!available()) {
            throw new ServiceException("场景助手已关闭，请联系管理员启用", 409);
        }
    }

    public View read() {
        SysConfig setting = stored();
        String configuredPool = pools.configuredPool(SCENE_CODE);
        List<String> eligiblePools = eligiblePools(null, false);
        if (automatic(setting)) {
            String readyPool = automaticPool(configuredPool);
            if (!readyPool.isEmpty()) return view(true, readyPool, eligiblePools);
        }
        return view(enabled(setting), configuredPool, eligiblePools);
    }

    @Transactional(rollbackFor = Exception.class)
    public View update(boolean enabled, String actor) {
        if (scenes.lockScene(SCENE_CODE) == null) {
            throw new ServiceException("预演台业务尚未初始化", 409);
        }
        SysConfig previous = stored();
        var pool = pools.read(SCENE_CODE);
        List<String> eligiblePools = eligiblePools(null, false);
        View status = view(enabled(previous), pool.poolCode(), eligiblePools);
        if (enabled && !status.canEnable()) {
            throw new ServiceException(status.message(), 409);
        }
        String poolCode = pool.poolCode();
        if (enabled && poolCode.isEmpty()) {
            poolCode = eligiblePools.get(0);
            pools.save(new BusinessSceneComputePolicyService.Save(SCENE_CODE, poolCode, pool.revision()), actor);
        }
        String value = Boolean.toString(enabled);
        if (previous == null || !value.equals(previous.getConfigValue())) {
            if (previous == null) {
                var created = new SysConfig();
                created.setConfigKey(KEY);
                created.setConfigName("预演台场景助手");
                created.setConfigType("N");
                created.setConfigValue(value);
                created.setCreateBy(actor);
                settings.insertConfig(created);
            } else if (scenes.writeParameterValue(previous.getConfigId(), KEY, value, actor) != 1) {
                throw new ServiceException("场景助手开关已变化，请重试", 409);
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    cache.deleteObject(CacheConstants.SYS_CONFIG_KEY + KEY);
                }
            });
        }
        return view(enabled, poolCode, eligiblePools);
    }

    private View view(boolean enabled, String poolCode, List<String> eligiblePools) {
        String message = "";
        if (eligiblePools.isEmpty()) {
            message = "请添加并启用自己的计算资源，节点需要支持三维构建";
        } else if (!runtimeEnabled) {
            message = "当前服务未启用预演台运行时";
        } else if (!poolCode.isEmpty() && !eligiblePools.contains(poolCode)) {
            message = "当前预演台资源池没有已启用的三维构建节点，请检查业务的计算资源池";
        } else if (poolCode.isEmpty() && eligiblePools.size() > 1) {
            message = "请在业务编排中为预演台选择计算资源池";
        }
        return new View(enabled, enabled && runtimeEnabled && eligiblePools.contains(poolCode), message.isEmpty(), poolCode, message);
    }

    private List<String> eligiblePools(String poolCode, boolean requireReady) {
        Set<String> codes = new TreeSet<>();
        var query = Wrappers.<AidComputeNode>lambdaQuery()
                .select(AidComputeNode::getPoolCode, AidComputeNode::getActiveConfigJson)
                .eq(poolCode != null, AidComputeNode::getPoolCode, poolCode)
                .eq(AidComputeNode::getState, "ACTIVE").isNotNull(AidComputeNode::getActiveRevision);
        if (requireReady) {
            query.eq(AidComputeNode::getHealthStatus, "ONLINE")
                    .ge(AidComputeNode::getHealthCheckedAt, new Date(System.currentTimeMillis() - healthPolicy.freshnessMillis()));
        }
        for (AidComputeNode node : nodes.selectList(query)) {
            var config = JSON.parseObject(node.getActiveConfigJson(), ComputeNodeConfiguration.class);
            var limits = config.limits();
            if (config.slots().stream().anyMatch(slot -> BUILD_ENGINES.contains(slot.engine())
                    && "build".equals(slot.taskKind())
                    && limits.engineConcurrent().getOrDefault(slot.engine(), limits.maxConcurrent()) > 0
                    && limits.taskConcurrent().getOrDefault("build", limits.maxConcurrent()) > 0)) {
                codes.add(node.getPoolCode());
            }
        }
        return List.copyOf(codes);
    }
}
