package com.aid.compute;

import com.aid.businessscene.BusinessSceneComputePolicyService;
import com.aid.businessscene.mapper.AidBusinessEntrypointMapper;
import com.aid.common.core.redis.RedisCache;
import com.aid.common.exception.ServiceException;
import com.aid.compute.domain.AidComputeNode;
import com.aid.compute.mapper.AidComputeNodeMapper;
import com.aid.core.domain.SysConfig;
import com.aid.system.mapper.SysConfigMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SceneAssistantSettingsTest {
    private static final String SCENE = SceneAssistantSettings.SCENE_CODE;
    private final SysConfigMapper config = mock(SysConfigMapper.class);
    private final AidBusinessEntrypointMapper scenes = mock(AidBusinessEntrypointMapper.class);
    private final RedisCache cache = mock(RedisCache.class);
    private final AidComputeNodeMapper nodes = mock(AidComputeNodeMapper.class);
    private final BusinessSceneComputePolicyService pools = mock(BusinessSceneComputePolicyService.class);

    @BeforeAll
    static void initializeComputeNodeMapping() {
        var mapper = new MapperBuilderAssistant(new MybatisConfiguration(), "compute-node-test");
        TableInfoHelper.initTableInfo(mapper, AidComputeNode.class);
    }

    @Test
    void noNodeCannotEnableAndAvailabilityDefaultsToFalse() {
        when(scenes.lockScene(SCENE)).thenReturn(SCENE);
        when(pools.read(SCENE)).thenReturn(new BusinessSceneComputePolicyService.View(SCENE, "", "unbound", List.of()));
        when(pools.configuredPool(SCENE)).thenReturn("");
        when(nodes.selectList(any())).thenReturn(List.of());
        assertFalse(service(true).available());
        assertFalse(service(true).read().canEnable());
        assertThrows(ServiceException.class, () -> service(true).update(true, "tester"));
    }

    @Test
    void eligibleNodeEnablesAndSelectsTheOnlyPool() {
        when(scenes.lockScene(SCENE)).thenReturn(SCENE);
        when(pools.read(SCENE)).thenReturn(new BusinessSceneComputePolicyService.View(SCENE, "", "initial", List.of()));
        when(nodes.selectList(any())).thenReturn(List.of(buildNode("worker")));
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service(true).update(true, "tester").available());
            verify(pools).save(new BusinessSceneComputePolicyService.Save(SCENE, "worker", "initial"), "tester");
            verify(config).insertConfig(any(SysConfig.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void savedFlagCannotEnableAStoppedRuntime() {
        var stored = new SysConfig();
        stored.setConfigValue("true");
        when(config.selectConfig(any())).thenReturn(stored);
        assertFalse(service(false).available());
        assertThrows(ServiceException.class, () -> service(false).requireEnabled());
    }

    @Test
    void automaticModeUsesTheOnlyReadyPoolWithoutAnAdministratorAction() {
        automaticMode();
        when(nodes.selectList(any())).thenReturn(List.of(buildNode("worker")));
        var service = service(true);
        assertTrue(service.available());
        assertTrue(service.read().available());
        assertEquals("worker", service.poolForNewBuild());
    }

    @Test
    void automaticModeRequiresAReadyPoolAndHonorsExplicitUnbinding() {
        automaticMode();
        when(nodes.selectList(any())).thenReturn(List.of());
        var service = service(true);
        assertFalse(service.available());
        assertThrows(ComputeResourcesUnavailableException.class, service::poolForNewBuild);
        when(nodes.selectList(any())).thenReturn(List.of(buildNode("worker-a"), buildNode("worker-b")));
        assertFalse(service.available());
        when(nodes.selectList(any())).thenReturn(List.of(buildNode("worker")));
        when(pools.managed(SCENE)).thenReturn(true);
        assertFalse(service.available());
        assertThrows(ComputeResourcesUnavailableException.class, service::poolForNewBuild);
    }

    @Test
    void installerCannotOpenAnAdministratorsExplicitlyDisabledSceneAssistant() {
        var setting = new SysConfig();
        setting.setConfigValue("false");
        when(config.selectConfig(any())).thenReturn(setting);
        when(nodes.selectList(any())).thenReturn(List.of(buildNode("worker")));
        when(pools.configuredPool(SCENE)).thenReturn("worker");
        assertFalse(service(true).available());
    }

    private void automaticMode() {
        var setting = new SysConfig();
        setting.setConfigValue("auto");
        when(config.selectConfig(any())).thenReturn(setting);
        when(pools.configuredPool(SCENE)).thenReturn("");
    }

    private AidComputeNode buildNode(String pool) {
        var amounts = new ComputeResourcePolicy.Resources(1000, 1000, 1000);
        var limits = new ComputeResourcePolicy.Limits(1, Map.of(), Map.of(), amounts, 0);
        var node = new AidComputeNode();
        node.setPoolCode(pool);
        node.setActiveConfigJson(JSON.toJSONString(new ComputeNodeConfiguration(
                "Test Node", "http://127.0.0.1:9000", "worker", null, Set.of("threejs"),
                limits, 60, 1, "local", List.of(new ComputeNodeConfiguration.Slot("threejs", "build", amounts, 60)))));
        return node;
    }

    private SceneAssistantSettings service(boolean runtimeEnabled) {
        return new SceneAssistantSettings(config, scenes, cache, nodes, pools, runtimeEnabled, runtimeEnabled,
                new ComputeHealthPolicy());
    }
}
