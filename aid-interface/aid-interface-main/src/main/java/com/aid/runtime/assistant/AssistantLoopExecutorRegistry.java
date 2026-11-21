package com.aid.runtime.assistant;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** Unique protocol-to-executor registry. */
@Slf4j
@Component
public class AssistantLoopExecutorRegistry
{
    private final List<AssistantLoopExecutor> executors;

    @Autowired
    public AssistantLoopExecutorRegistry(@Autowired(required = false) List<AssistantLoopExecutor> executors)
    {
        this.executors = executors == null ? Collections.emptyList() : executors;
    }

    public boolean isAvailable(String protocolVersion)
    {
        return matching(protocolVersion).size() == 1;
    }

    public AssistantLoopExecutor require(String protocolVersion)
    {
        List<AssistantLoopExecutor> matching = matching(protocolVersion);
        if (matching.size() != 1)
        {
            log.info("助手执行器数量异常, protocolVersion={}, count={}", protocolVersion, matching.size());
            throw new ServiceException("执行器不可用");
        }
        return matching.get(0);
    }

    private List<AssistantLoopExecutor> matching(String protocolVersion)
    {
        if (StrUtil.isBlank(protocolVersion)) return Collections.emptyList();
        return executors.stream()
                .filter(executor -> protocolVersion.equals(executor.supportedProtocolVersion()))
                .collect(Collectors.toList());
    }
}
