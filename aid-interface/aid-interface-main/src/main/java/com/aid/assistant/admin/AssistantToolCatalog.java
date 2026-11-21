package com.aid.assistant.admin;

import com.aid.assistant.admin.AssistantAdminViews.ToolCatalogItem;
import com.aid.runtime.service.RuntimeToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

/** Code-owned catalog. It intentionally exposes no execution method. */
@Component
public class AssistantToolCatalog
{
    private final RuntimeToolRegistry registry;

    public AssistantToolCatalog(RuntimeToolRegistry registry)
    {
        this.registry = registry;
    }

    public List<ToolCatalogItem> list()
    {
        return registry.options().stream().map(this::tool).toList();
    }

    private ToolCatalogItem tool(RuntimeToolRegistry.Descriptor descriptor)
    {
        ToolCatalogItem item = new ToolCatalogItem();
        item.setCode(descriptor.code());
        item.setContractVersion(descriptor.contractVersion());
        item.setSchemaDigest(descriptor.schemaDigest());
        item.setSchemaVersion(1);
        item.setName(descriptor.name());
        item.setDescription(descriptor.description());
        item.setInputSchemaSummary(descriptor.inputSchema());
        item.setOutputSchemaSummary(descriptor.outputSchema());
        item.setObjectScopes(List.of());
        item.setSideEffectLevel(descriptor.sideEffectLevel());
        item.setBillable(descriptor.billable());
        item.setIdempotencyLevel(descriptor.idempotencyLevel());
        item.setCancellable(descriptor.cancellable());
        item.setRecoveryPolicy(descriptor.recoveryPolicy());
        item.setRequiredCapabilities(descriptor.requiredCapabilities());
        item.setEnabled(false);
        item.setDisabledReason("公共运行执行器尚未启用");
        item.setImplementationVersion("public-catalog-v1");
        return item;
    }
}
