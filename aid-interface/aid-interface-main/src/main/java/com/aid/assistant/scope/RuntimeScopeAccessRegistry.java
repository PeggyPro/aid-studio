package com.aid.assistant.scope;

import cn.hutool.core.collection.CollectionUtil;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.error.AssistantServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Fail-closed registry for optional context access adapters. */
@Slf4j
@Component
public class RuntimeScopeAccessRegistry
{
    private final List<RuntimeScopeAccess> adapters;

    @Autowired
    public RuntimeScopeAccessRegistry(@Autowired(required = false) List<RuntimeScopeAccess> adapters)
    {
        this.adapters = adapters == null ? Collections.emptyList() : adapters;
    }

    public boolean supports(String kind)
    {
        return adapters.stream().filter(adapter -> adapter.supports(kind)).count() == 1;
    }

    public void verifyAll(Long userId, List<AssistantRequests.ContextRef> contextRefs)
    {
        resolvePinnedAll(userId, contextRefs);
    }

    public List<ResolvedContextRef> resolvePinnedAll(Long userId,
                                                      List<AssistantRequests.ContextRef> contextRefs)
    {
        return resolveAll(userId, contextRefs, true);
    }

    public List<ResolvedContextRef> resolveCurrentAll(Long userId,
                                                       List<AssistantRequests.ContextRef> contextRefs)
    {
        return resolveAll(userId, contextRefs, false);
    }

    private List<ResolvedContextRef> resolveAll(Long userId,
                                                 List<AssistantRequests.ContextRef> contextRefs,
                                                 boolean pinned)
    {
        if (CollectionUtil.isEmpty(contextRefs)) return List.of();
        List<ResolvedContextRef> result = new ArrayList<>();
        for (AssistantRequests.ContextRef contextRef : contextRefs)
        {
            if (contextRef == null)
                throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        "ASSISTANT_SCOPE_UNAVAILABLE", "\u5f53\u524d\u8303\u56f4\u4e0d\u53ef\u7528", null);
            List<RuntimeScopeAccess> matching = adapters.stream()
                    .filter(item -> item.supports(contextRef.getKind()))
                    .collect(Collectors.toList());
            if (matching.size() != 1)
            {
                log.info("助手范围适配器数量异常, userId={}, kind={}, count={}",
                        userId, contextRef.getKind(), matching.size());
                throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        "ASSISTANT_SCOPE_UNAVAILABLE", "当前范围不可用", contextRef.getKind());
            }
            ResolvedContextRef resolved = pinned
                    ? matching.get(0).resolvePinned(userId, contextRef)
                    : matching.get(0).resolveCurrent(userId, contextRef);
            if (resolved == null || resolved.contextRef() == null
                    || !contextRef.getKind().equals(resolved.contextRef().getKind())
                    || resolved.contentRef() == null || resolved.contentRef().isBlank())
                throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        "ASSISTANT_SCOPE_UNAVAILABLE", "\u5f53\u524d\u8303\u56f4\u4e0d\u53ef\u7528", contextRef.getKind());
            result.add(resolved);
        }
        return result;
    }
}
