package com.aid.model.definition;

import com.aid.aid.domain.AidAiModelAlias;
import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelProtocolBinding;
import com.aid.common.exception.ServiceException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelHistoricalAliasRouteRegressionTest {
    private static ModelCapabilityDefinition capability(String code, String... routeCodes) {
        var capability = new ModelCapabilityDefinition(); capability.setCode(code);
        capability.setBindings(java.util.Arrays.stream(routeCodes).map(name -> {
            var route = new ModelProtocolBinding(); route.setCode(name); return route;
        }).toList());
        return capability;
    }
    private static AidAiModelAlias alias(String capability, String route) {
        var alias = new AidAiModelAlias(); alias.setId(75L); alias.setModelId(450L);
        alias.setLegacyModelId(95L); alias.setLegacyModelCode("deepseek-v4-flash");
        alias.setCapabilityCode(capability); alias.setBindingCode(route); return alias;
    }
    private static void verify(List<ModelCapabilityDefinition> before, List<ModelCapabilityDefinition> after, AidAiModelAlias... aliases) {
        ModelDefinitionService.assertHistoricalAliasRoutesRetained(before, after, List.of(aliases));
    }
    @Test void existingOrphanDoesNotBlockCurrentRouteParameterSaveOrRewriteHistory() {
        var old = alias("text", "route_95");
        var before = List.of(capability("text", "deepseek_chat"), capability("fim", "deepseek_fim"));
        var after = List.of(capability("text", "deepseek_chat"), capability("fim", "deepseek_fim"));
        after.get(0).getBindings().get(0).setFixedParameters(java.util.Map.of("max_tokens", 65536));
        assertDoesNotThrow(() -> verify(before, after, old));
        assertEquals("route_95", old.getBindingCode()); assertEquals("text", old.getCapabilityCode());
        assertEquals(95L, old.getLegacyModelId()); assertEquals(450L, old.getModelId());
    }
    @Test void deletingAnExistingReferencedProtocolIsStillRejected() {
        var before = List.of(capability("text", "route_95", "deepseek_chat"));
        var after = List.of(capability("text", "deepseek_chat"));
        assertThrows(ServiceException.class, () -> verify(before, after, alias("text", "route_95")));
    }
    @Test void deletingAReferencedCapabilityIsStillRejected() {
        assertThrows(ServiceException.class, () -> verify(List.of(capability("text", "route_95")),
                List.of(capability("fim", "deepseek_fim")), alias("text", "route_95")));
    }
    @Test void sameBindingUnderAnotherCapabilityCannotSatisfyHistory() {
        assertThrows(ServiceException.class, () -> verify(List.of(capability("text", "route_95")),
                List.of(capability("text", "deepseek_chat"), capability("fim", "route_95")), alias("text", "route_95")));
    }
    @Test void mixedOrphanAndValidAliasCannotBypassRemovalProtection() {
        assertThrows(ServiceException.class, () -> verify(List.of(capability("text", "deepseek_chat")),
                List.of(capability("text", "replacement")), alias("text", "route_95"), alias("text", "deepseek_chat")));
    }
    @Test void preservingTheReferencedRouteAllowsOtherRouteChanges() {
        assertDoesNotThrow(() -> verify(List.of(capability("text", "route_95", "other")),
                List.of(capability("text", "route_95", "new")), alias("text", "route_95")));
    }
    @Test void creationCannotUseTheExistingOrphanException() {
        assertThrows(ServiceException.class, () -> verify(null, List.of(capability("text", "deepseek_chat")), alias("text", "route_95")));
        assertDoesNotThrow(() -> verify(null, List.of(capability("text", "route_95")), alias("text", "route_95")));
    }
    @Test void preExistingMissingCapabilityDoesNotBlockUnrelatedValidDefinitions() {
        assertDoesNotThrow(() -> verify(List.of(capability("text", "deepseek_chat")),
                List.of(capability("text", "deepseek_chat")), alias("historical_missing", "route_95")));
    }
}
