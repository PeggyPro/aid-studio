package com.aid.assistant.realtime;

import com.aid.assistant.error.AssistantServiceException;
import com.aid.assistant.service.AssistantCoreService;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AssistantReasoningHistoryService {
    private final AssistantCoreService sessions;
    private final AidRuntimeRunMapper runs;
    private final AidRuntimeRunStepMapper steps;
    private final AssistantReasoningArchive archive;
    private final com.aid.runtime.assistant.NativeAssistantReasoningHistory nativeHistory;

    @Data public static class Read {
        @NotNull @Min(1) private Long sessionId;
        @NotNull @Min(1) private Long runId;
        @NotNull @Min(1) private Long stepId;
        @Pattern(regexp="[a-f0-9]{64}") private String stream;
        @Min(1) private Long innerStepId;
        @Min(1) private Long skillRunId;
        @Min(0) @Max(1000000) private int offset;
        @Min(1) @Max(64) private int limit=16;
    }

    public Map<String,Object> read(Long userId,Read request) {
        if((request.getInnerStepId()==null)!=(request.getSkillRunId()==null))throw new AssistantServiceException(400,"REASONING_PAGE_INVALID","Skill思考记录身份不完整",null);
        var authorizedSession = sessions.authorizeSession(userId,request.getSessionId(),false);
        AidRuntimeRun run=runs.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .select(AidRuntimeRun::getId, AidRuntimeRun::getUserId, AidRuntimeRun::getVersionDigest).eq(AidRuntimeRun::getId,request.getRunId())
                .eq(AidRuntimeRun::getSessionId,request.getSessionId()).eq(AidRuntimeRun::getUserId,userId)
                .eq(AidRuntimeRun::getDelFlag,"0"));
        AidRuntimeRunStep step=run==null?null:steps.selectOne(new LambdaQueryWrapper<AidRuntimeRunStep>()
                .select(AidRuntimeRunStep::getId, AidRuntimeRunStep::getStepKind, AidRuntimeRunStep::getMediaTaskId).eq(AidRuntimeRunStep::getId,request.getStepId())
                .eq(AidRuntimeRunStep::getRunId,request.getRunId()).eq(AidRuntimeRunStep::getDelFlag,"0"));
        if(step==null)throw new AssistantServiceException(404,"REASONING_STEP_NOT_FOUND","该步骤的思考记录不可访问",null);
        try{
            Map<String,Object> result=new java.util.LinkedHashMap<>(archive.read(request.getSessionId(),request.getRunId(),request.getStepId(),request.getStream(),request.getOffset(),request.getLimit(),request.getInnerStepId(),request.getSkillRunId()));
            Object chunks = result.get("chunks");
            boolean empty = !(chunks instanceof java.util.Collection<?> records) || records.isEmpty();
            if (Boolean.TRUE.equals(authorizedSession.getShowReasoning()) && request.getInnerStepId() == null && empty && !Boolean.TRUE.equals(result.get("hasMore"))
                    && java.util.Set.of("COMPLETE", "UNAVAILABLE").contains(result.get("status"))) {
                var recovered = nativeHistory.read(run, step, request.getStream(), request.getOffset(), Math.min(16, request.getLimit()));
                if (recovered != null) result = new java.util.LinkedHashMap<>(recovered);
            }
            Map<String,String> scope=new java.util.LinkedHashMap<>(Map.of("sessionId",request.getSessionId().toString(),"runId",request.getRunId().toString(),"stepId",request.getStepId().toString()));
            if(request.getInnerStepId()!=null){scope.put("innerStepId",request.getInnerStepId().toString());scope.put("skillRunId",request.getSkillRunId().toString());}
            result.put("scope",scope);
            return result;
        }
        catch(com.aid.common.exception.ServiceException failure){throw new AssistantServiceException(503,"REASONING_ARCHIVE_UNAVAILABLE","思考记录暂时无法读取，请稍后重试",null);}
        catch(IOException failure){throw new AssistantServiceException(503,"REASONING_ARCHIVE_UNAVAILABLE","思考记录暂时无法读取，请稍后重试",null);}
        catch(IllegalArgumentException invalid){throw new AssistantServiceException(400,"REASONING_PAGE_INVALID","思考记录分页参数无效",null);}
    }
}
