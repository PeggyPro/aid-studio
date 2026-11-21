package com.aid.runtime.service;

import com.aid.common.exception.ServiceException;
import com.aid.runtime.dto.RuntimeAgentRequests;
import com.aid.runtime.tool.ConversationHistoryRuntimeToolProvider;
import com.aid.runtime.tool.SharedContextRuntimeToolProvider;
import java.util.ArrayList;
import java.util.Objects;

/** Explicit defaults in NEW drafts; never changes an already frozen Agent version. */
public final class ConversationAgentDefaults {
    private ConversationAgentDefaults() { }

    public static void apply(RuntimeAgentRequests.DraftPayload draft) {
        apply(draft, false);
    }

    public static void apply(RuntimeAgentRequests.DraftPayload draft, boolean supportsPreparation) {
        if (!"ASSISTANT_LOOP".equals(draft.getExecutorType())) return;
        var bindings = draft.getToolBindings() == null
                ? new ArrayList<RuntimeAgentRequests.ToolBinding>() : new ArrayList<>(draft.getToolBindings());
        var legacy = ConversationHistoryRuntimeToolProvider.descriptor();
        bindings.removeIf(binding -> binding != null && legacy.code().equals(binding.getToolCode())
                && Objects.equals(legacy.contractVersion(), binding.getContractVersion())
                && Objects.equals(legacy.schemaDigest(), binding.getSchemaDigest()));
        if (bindings.stream().noneMatch(binding -> binding != null
                && SharedContextRuntimeToolProvider.isContextReaderCode(binding.getToolCode()))) {
            var descriptor = com.aid.runtime.tool.SharedContextReadV2ToolProvider.descriptor();
            var binding = new RuntimeAgentRequests.ToolBinding();
            binding.setToolCode(descriptor.code()); binding.setContractVersion(descriptor.contractVersion());
            binding.setSchemaDigest(descriptor.schemaDigest()); bindings.add(binding);
        }
        draft.setToolBindings(bindings);
        if (supportsPreparation && bindings.stream().noneMatch(binding -> binding != null
                && com.aid.runtime.tool.ContextPreparationRuntimeToolProvider.CODE.equals(binding.getToolCode()))) {
            var descriptor = com.aid.runtime.tool.ContextPreparationRuntimeToolProvider.descriptor();
            var binding = new RuntimeAgentRequests.ToolBinding();
            binding.setToolCode(descriptor.code()); binding.setContractVersion(descriptor.contractVersion());
            binding.setSchemaDigest(descriptor.schemaDigest()); bindings.add(binding);
        }
    }

    public static void requireForPublication(RuntimeAgentRequests.DraftPayload draft) {
        requireForPublication(draft, false);
    }

    public static void requireForPublication(RuntimeAgentRequests.DraftPayload draft, boolean supportsPreparation) {
        if (!"ASSISTANT_LOOP".equals(draft.getExecutorType())) return;
        if (draft.getToolBindings() == null || draft.getToolBindings().stream().noneMatch(ConversationAgentDefaults::validContextBinding))
            throw new ServiceException("请重新保存Agent草稿以包含通用上下文读取能力");
        var preparation = com.aid.runtime.tool.ContextPreparationRuntimeToolProvider.descriptor();
        var preparing = draft.getToolBindings().stream().filter(binding -> binding != null
                && preparation.code().equals(binding.getToolCode())).toList();
        if (!supportsPreparation && !preparing.isEmpty())
            throw new ServiceException("当前执行协议不支持上下文准备能力");
        if (supportsPreparation && (preparing.size() != 1
                || !preparation.contractVersion().equals(preparing.get(0).getContractVersion())
                || !preparation.schemaDigest().equals(preparing.get(0).getSchemaDigest())))
            throw new ServiceException("请重新保存Agent草稿以包含通用上下文准备能力");
    }
    private static boolean validContextBinding(RuntimeAgentRequests.ToolBinding binding) {
        if (binding == null) return false;
        var descriptor = SharedContextRuntimeToolProvider.descriptorForCode(binding.getToolCode());
        return descriptor != null && Objects.equals(descriptor.contractVersion(), binding.getContractVersion())
                && Objects.equals(descriptor.schemaDigest(), binding.getSchemaDigest());
    }
}
