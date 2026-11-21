package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Resolves native JSON Schema support from the uniquely registered text protocol adapter. */
@Component
@RequiredArgsConstructor
public class TextNativeSchemaTransportSupport
{
    private final List<TextProviderClient> clients;

    public boolean supports(String protocol)
    {
        if (StrUtil.isBlank(protocol)) return false;
        List<TextProviderClient> matches = clients.stream()
                .filter(client -> client.supportsProtocol(protocol)).toList();
        return matches.size() == 1 && matches.get(0).supportsNativeJsonSchema();
    }
}
