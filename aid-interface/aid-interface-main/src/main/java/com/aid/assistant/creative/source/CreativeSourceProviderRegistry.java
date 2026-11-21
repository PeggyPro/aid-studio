package com.aid.assistant.creative.source;

import com.aid.assistant.error.AssistantServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Explicit provider allowlist. Absence means that source kind is dependency-blocked. */
@Component
public class CreativeSourceProviderRegistry {
    private final Map<String, CreativeSourceProvider> providers;

    public CreativeSourceProviderRegistry(List<CreativeSourceProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                provider -> provider.sourceKind().toUpperCase(Locale.ROOT), Function.identity()));
    }

    public CreativeSourceProvider require(String sourceKind) {
        CreativeSourceProvider provider = sourceKind == null ? null
                : providers.get(sourceKind.toUpperCase(Locale.ROOT));
        if (provider == null) {
            throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "CREATIVE_SOURCE_UNAVAILABLE", "Source type is not connected", sourceKind);
        }
        return provider;
    }
}
