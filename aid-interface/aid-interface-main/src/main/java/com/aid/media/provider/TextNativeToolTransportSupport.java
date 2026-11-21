package com.aid.media.provider;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Resolves native tool support from the uniquely registered text protocol adapter. */
@Component
@RequiredArgsConstructor
public class TextNativeToolTransportSupport
{
    private final List<TextProviderClient> clients;

    public boolean supports(String protocol)
    {
        TextNativeToolProtocol.ProtocolContract contract = StrUtil.isBlank(protocol)
                ? null : TextNativeToolProtocol.contract(protocol);
        if (contract == null) return false;
        List<TextProviderClient> matches = clients.stream()
                .filter(client -> client.supportsProtocol(protocol)).toList();
        return matches.size() == 1 && matches.get(0).supportsNativeToolCalling()
                && contract.providerProtocol().equalsIgnoreCase(matches.get(0).protocol());
    }
}
