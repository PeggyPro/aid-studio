package com.aid.previz.service;

import com.aid.compute.SceneAssistantSettings;
import com.aid.runtime.assistant.AssistantModuleExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 独立三维搭建模块，原空间方案助手保持只读。 */
@Component
@ConditionalOnProperty(prefix = "aid.previz.runtime", name = "enabled", havingValue = "true")
public final class PrevizAssistantModule implements AssistantModuleExtension
{
    private final SceneAssistantSettings settings;
    private final org.springframework.beans.factory.ObjectProvider<com.aid.businessscene.BusinessEntrypointService> sceneConfigurations;

    public PrevizAssistantModule(SceneAssistantSettings settings,
                                org.springframework.beans.factory.ObjectProvider<com.aid.businessscene.BusinessEntrypointService> sceneConfigurations) {
        this.settings = settings;
        this.sceneConfigurations = sceneConfigurations;
    }
    @Override public com.aid.runtime.assistant.AssistantLoopBinding constrainActiveBinding(com.aid.runtime.assistant.AssistantLoopBinding binding){
        settings.requireEnabled();
        var scene=sceneConfigurations.getObject().read(SceneAssistantSettings.SCENE_CODE);if(scene.bindings().isEmpty())return binding;
        var entry=scene.bindings().stream().filter(item->item.available()&&item.binding().defaultEntry()&&"AGENT".equals(item.binding().kind())
            &&binding.getAgentCode().equals(item.binding().code())&&String.valueOf(binding.getAgentVersionId()).equals(item.binding().versionId())).findFirst()
            .orElseThrow(()->new com.aid.common.exception.ServiceException("当前业务固定Agent绑定不可用"));
        var config=com.alibaba.fastjson2.JSON.parseObject(binding.getModelConfigJson());
        var declared=config.getList("selectableModelCodes",String.class);
        var allowed=declared.stream().filter(entry.allowedModelCodes()::contains).toList();
        if(allowed.isEmpty())throw new com.aid.common.exception.ServiceException("当前业务没有可用的授权文本模型");
        String selected=config.getString("defaultModelCode");if(!allowed.contains(selected))selected=allowed.get(0);
        config.put("selectableModelCodes",allowed);config.put("defaultModelCode",selected);
        var constrained=new com.aid.runtime.assistant.AssistantLoopBinding();org.springframework.beans.BeanUtils.copyProperties(binding,constrained);constrained.setModelConfigJson(config.toJSONString());return constrained;
    }
    @Override public String moduleCode() { return "PREVIZ_3DBOX"; }
    @Override public java.util.Set<String> retainedReceiptToolCodes(){return java.util.Set.of(PrevizRuntimeToolProvider.WRITE,PrevizRuntimeToolProvider.EDIT,"aid.previz.source-import",PrevizSceneEditToolProvider.CODE);}
    @Override public String agentCode() { return "previz_3dbox_agent"; }
    @Override public boolean supportsTransport(String transport) { return "MAIN".equals(transport); }
    @Override public boolean supportsStreaming(String transport) { return "MAIN".equals(transport); }
    @Override public String realtimeWebSocketPath(String transport) { return "MAIN".equals(transport) ? "/ws/previz/assistant" : null; }
    @Override public java.util.Set<String> optionalDiscoveryToolCodes() {
        return java.util.Set.of(PrevizResourceToolProvider.CODE);
    }
    @Override public boolean supportsConversationBudget() { return true; }
    @Override public boolean automaticExecutionRequired() { return true; }
    @Override public boolean usesExecutionBudget() { return false; }
}
