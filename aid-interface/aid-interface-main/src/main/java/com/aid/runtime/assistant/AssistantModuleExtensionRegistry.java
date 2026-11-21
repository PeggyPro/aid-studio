package com.aid.runtime.assistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class AssistantModuleExtensionRegistry
{
    private final Map<String, String> modules;
    private final Map<String, AssistantModuleExtension> extensions;

    @Autowired
    public AssistantModuleExtensionRegistry(@Autowired(required = false) List<AssistantModuleExtension> extensions)
    {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, AssistantModuleExtension> custom = new LinkedHashMap<>();
        values.put("UNIVERSAL", "universal_canvas_agent");
        values.put("SCREENWRITER", "story_script_agent");
        values.put("SPATIAL_PREVIS", "spatial_previs_agent");
        if (extensions != null) for (AssistantModuleExtension extension : extensions)
        {
            String code = extension.moduleCode(), agent = extension.agentCode();
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}") || agent == null
                    || !agent.matches("[a-z][a-z0-9_-]{1,99}") || values.putIfAbsent(code, agent) != null)
                throw new IllegalStateException("助手模块扩展注册冲突或无效");
            custom.put(code, extension);
        }
        modules = java.util.Collections.unmodifiableMap(values);
        this.extensions = Map.copyOf(custom);
    }

    public List<String> moduleCodes() { return List.copyOf(modules.keySet()); }
    public List<String> moduleCodes(String transport) {
        return modules.keySet().stream().filter(code -> !extensions.containsKey(code) || extensions.get(code).supportsTransport(transport)).toList();
    }
    public String agentCode(String moduleCode) { return modules.get(moduleCode); }
    public java.util.Set<String> retainedReceiptToolCodes(String moduleCode){
        var extension=extensions.get(moduleCode);return extension==null?java.util.Set.of():java.util.Set.copyOf(extension.retainedReceiptToolCodes());
    }
    public AssistantLoopBinding constrainActiveBinding(AssistantLoopBinding binding){
        var extension=extensions.get(binding.getModuleCode());return extension==null?binding:extension.constrainActiveBinding(binding);
    }
    public boolean isOptionalDiscoveryTool(String moduleCode, String toolCode) {
        AssistantModuleExtension extension = moduleCode == null ? null : extensions.get(moduleCode);
        return extension != null && extension.optionalDiscoveryToolCodes().contains(toolCode);
    }

    public boolean supportsConversationBudget(String moduleCode) {
        AssistantModuleExtension extension = extensions.get(moduleCode);
        return extension != null && extension.supportsConversationBudget();
    }
    public boolean automaticExecutionRequired(String moduleCode) {
        if (SpatialPrevisGuidancePolicy.appliesToModule(moduleCode)) return true;
        AssistantModuleExtension extension = extensions.get(moduleCode);
        return extension != null && extension.automaticExecutionRequired();
    }
    public boolean usesExecutionBudget(String moduleCode) {
        if (SpatialPrevisGuidancePolicy.appliesToModule(moduleCode)) return false;
        AssistantModuleExtension extension = extensions.get(moduleCode);
        return extension == null || extension.usesExecutionBudget();
    }
    public boolean supportsStreaming(String moduleCode, String transport) {
        AssistantModuleExtension extension = extensions.get(moduleCode);
        return extension != null && extension.supportsTransport(transport) && extension.supportsStreaming(transport);
    }
    public String realtimeWebSocketPath(String moduleCode, String transport) {
        if (!supportsStreaming(moduleCode, transport)) return null;
        String path = extensions.get(moduleCode).realtimeWebSocketPath(transport);
        if (path != null && !path.matches("/ws/[a-zA-Z0-9/_-]{1,120}"))
            throw new IllegalStateException("助手实时路径无效");
        return path;
    }
}
