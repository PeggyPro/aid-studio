package com.aid.assistant.effect;

import com.aid.assistant.artifact.domain.AidRuntimeArtifact;
import com.aid.assistant.domain.AidAssistantSession;
import com.aid.assistant.dto.AssistantRequests;
import com.aid.assistant.vo.AssistantTargetViews;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;

import java.util.List;
import java.util.Map;

public interface AssistantTargetEffectService {
    Map<Long, List<AssistantTargetViews.TargetEffect>> projectSteps(
            Long userId, AidAssistantSession session, AidRuntimeRun run, List<AidRuntimeRunStep> steps);

    Map<Long, AssistantTargetViews.TargetEffect> projectArtifacts(
            Long userId, AidAssistantSession session, List<AidRuntimeArtifact> artifacts);

    Map<Long, AssistantTargetViews.TargetEffect> projectArtifacts(
            Long userId, List<AidRuntimeArtifact> artifacts);

    AssistantTargetViews.TargetEffect projectArtifact(Long userId, AidRuntimeArtifact artifact);

    AssistantTargetViews.EffectResolved resolve(Long userId, AssistantRequests.EffectResolve request);
}
