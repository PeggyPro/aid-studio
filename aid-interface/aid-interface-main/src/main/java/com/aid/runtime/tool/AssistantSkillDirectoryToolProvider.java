package com.aid.runtime.tool;

import cn.hutool.crypto.SecureUtil;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.mapper.AidAssistantSessionMapper;
import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AssistantSkillDirectoryMapper;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;

/** Official Skill discovery belongs to the current frozen universal Agent, never to a peer Agent. */
@Component
@RequiredArgsConstructor
public class AssistantSkillDirectoryToolProvider implements RuntimeToolProvider {
    public static final String CODE = "aid.skill.directory";
    private static final String INPUT = """
            {"type":"object","additionalProperties":false,"properties":{
              "keyword":{"type":"string","maxLength":100},
              "pageNum":{"type":"integer","minimum":1,"maximum":1000},
              "pageSize":{"type":"integer","minimum":1,"maximum":20}
            }}
            """;
    private static final String OUTPUT = """
            {"type":"object","required":["items","pageNum","pageSize","hasMore"],"properties":{
              "items":{"type":"array","maxItems":20,"items":{"type":"object"}},
              "pageNum":{"type":"integer"},"pageSize":{"type":"integer"},"hasMore":{"type":"boolean"}
            },"additionalProperties":false}
            """;
    private final AidRuntimeRunMapper runs;
    private final AidAssistantSessionMapper sessions;
    private final AssistantSkillDirectoryMapper directory;

    public List<RuntimeToolRegistry.Descriptor> descriptors() {
        return List.of(new RuntimeToolRegistry.Descriptor(CODE, "1.0.0", SecureUtil.sha256(INPUT + "\n" + OUTPUT),
                "查询官方Skill目录", "仅在用户询问可用Skill或需要查找方法时分页读取名称与简介，不读正文；不要自动遍历所有页。选定后调用aid.skill.resolve-version及aid.skill.read-resource。编剧助手是平级智能体，不在此目录，不能互相调用。",
                INPUT, OUTPUT, "READ", false, "REQUEST_DIGEST", false, "REPLAY_SAFE", List.of("TEXT")));
    }

    @Override
    public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor) {
        return OutputReferenceKind.OBSERVATION;
    }

    public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context, RuntimeToolRegistry.Descriptor descriptor) {
        return context != null && context.getUserId() != null && context.getUserId() > 0
                && "UNIVERSAL".equals(context.getModuleCode()) ? RuntimeToolAvailability.enabled()
                : RuntimeToolAvailability.unavailable("UNIVERSAL_AGENT_REQUIRED");
    }

    public RuntimeToolResult execute(RuntimeToolInvocation invocation) {
        if (invocation == null || !CODE.equals(invocation.getToolCode()) || invocation.getRunId() == null
                || invocation.getUserId() == null) throw new ServiceException("目录访问范围无效");
        JSONObject input = JSON.parseObject(invocation.getArgumentsJson());
        if (input == null) throw new ServiceException("目录查询参数无效");
        int page = input.containsKey("pageNum") ? input.getIntValue("pageNum") : 1;
        int size = input.containsKey("pageSize") ? input.getIntValue("pageSize") : 20;
        String keyword = input.getString("keyword");
        if (page < 1 || page > 1000 || size < 1 || size > 20 || keyword != null && keyword.length() > 100)
            throw new ServiceException("目录查询参数无效");
        AidRuntimeRun run = runs.selectOne(Wrappers.<AidRuntimeRun>lambdaQuery()
                .select(AidRuntimeRun::getId, AidRuntimeRun::getUserId, AidRuntimeRun::getSessionId,
                        AidRuntimeRun::getVersionId, AidRuntimeRun::getExecutorStrategy)
                .eq(AidRuntimeRun::getId, invocation.getRunId()).eq(AidRuntimeRun::getDelFlag, "0"));
        if (run == null || !Objects.equals(run.getUserId(), invocation.getUserId())
                || !"ASSISTANT_LOOP".equals(run.getExecutorStrategy())) throw new ServiceException("目录访问范围无效");
        AidAssistantSession session = sessions.selectOne(Wrappers.<AidAssistantSession>lambdaQuery()
                .select(AidAssistantSession::getId, AidAssistantSession::getUserId, AidAssistantSession::getModuleCode)
                .eq(AidAssistantSession::getId, run.getSessionId()).eq(AidAssistantSession::getDelFlag, "0")
                .eq(AidAssistantSession::getSessionType, "ASSISTANT"));
        if (session == null || !Objects.equals(session.getUserId(), invocation.getUserId())
                || !"UNIVERSAL".equals(session.getModuleCode())) throw new ServiceException("目录访问范围无效");
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim().replace("=", "==").replace("%", "=%").replace("_", "=_");
        var rows = directory.selectPage(run.getVersionId(), invocation.getUserId(), keyword, null,
                "OFFICIAL", false, (page - 1) * size, size + 1, null);
        JSONObject output = new JSONObject();
        output.put("items", rows.subList(0, Math.min(size, rows.size())));
        output.put("pageNum", page); output.put("pageSize", size); output.put("hasMore", rows.size() > size);
        return RuntimeToolResult.succeeded(output.toJSONString());
    }
}
