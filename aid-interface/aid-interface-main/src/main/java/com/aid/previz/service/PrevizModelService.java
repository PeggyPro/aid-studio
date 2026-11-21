package com.aid.previz.service;

import com.aid.common.exception.ServiceException;
import com.aid.previz.domain.PrevizProject;
import com.aid.runtime.assistant.AssistantAgentModelReadinessService;
import com.aid.runtime.assistant.AssistantLoopBinding;
import com.aid.runtime.assistant.AssistantModuleAgentResolver;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 从固定空间Agent版本解析可选文本模型。 */
@Service
@RequiredArgsConstructor
public class PrevizModelService
{
    private final PrevizProjectService projects;
    private final AssistantModuleAgentResolver bindings;
    private final AssistantAgentModelReadinessService readiness;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<com.aid.businessscene.BusinessEntrypointService> scenes;

    @Value("${aid.previz.default-model-code:}")
    private String preferredModelCode;

    public Map<String, Object> options(Long userId, String projectId)
    {
        PrevizProject project = projects.detail(userId, projectId);
        return resolveOptions(project.getModelCode());
    }

    public Map<String, Object> catalog(Long userId)
    {
        if (userId == null || userId <= 0) throw new ServiceException("请先登录", 401);
        return resolveOptions(null);
    }

    private Map<String, Object> resolveOptions(String projectModelCode)
    {
        AssistantLoopBinding binding = bindings.requireActive("PREVIZ_3DBOX");
        AssistantAgentModelReadinessService.Verification verified = readiness.verifyFrozen(
                binding.getModelConfigJson(), binding.getDefinitionJson());
        if (!verified.ready()) throw new ServiceException(verified.reason());
        String fallback = preferredModelCode == null || preferredModelCode.isBlank()
                ? verified.verifiedDefaultModel().getModelCode() : preferredModelCode;
        String selected = projectModelCode == null ? fallback : projectModelCode;
        if (!verified.verifiedModels().containsKey(selected))
            throw new ServiceException("工程文本模型未包含在当前Agent的可用模型配置中");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentVersionId", String.valueOf(binding.getAgentVersionId()));
        result.put("modelCode", selected);
        result.put("models", verified.verifiedModels().values().stream()
                .map(model -> Map.of("code", model.getModelCode(), "name",
                        model.getModelName() == null ? model.getModelCode() : model.getModelName())).toList());
        var sceneService=scenes.getIfAvailable();
        if(sceneService!=null){var scene=sceneService.read("previz_3dbox");if(!scene.bindings().isEmpty()){
            result.put("bindingRevision",scene.revision());
            result.put("models",scene.models().stream().filter(model->verified.verifiedModels().containsKey(model.getModelCode())).map(model->{
                var option=new LinkedHashMap<String,Object>();option.put("code",model.getModelCode());option.put("name",model.getModelName());
                option.put("capabilityCode",model.getCapabilityCode());option.put("parameterSchema",model.getParameterSchema());option.put("parameterRules",model.getParameterRules());option.put("billing",model.getBilling());return option;
            }).toList());
        }}
        return result;
    }

    public void requireModel(Long userId, String projectId, String modelCode)
    {
        projects.requireWrite(userId, projectId);
        AssistantLoopBinding binding = bindings.requireActive("PREVIZ_3DBOX");
        JSONObject config = JSON.parseObject(binding.getModelConfigJson());
        if (modelCode == null || modelCode.isBlank() || config == null
                || config.getList("selectableModelCodes", String.class) == null
                || !config.getList("selectableModelCodes", String.class).contains(modelCode))
            throw new ServiceException("请选择当前Agent已授权的文本模型");
        config.put("defaultModelCode", modelCode);
        AssistantAgentModelReadinessService.Verification verified = readiness.verifyFrozen(config.toJSONString(), binding.getDefinitionJson());
        if (!verified.ready()) throw new ServiceException(verified.reason());
    }
}
