package com.aid.assistant.artifact.service;

import com.aid.assistant.error.AssistantServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ArtifactAdoptionTargetRegistry {
    private final Map<String, ArtifactAdoptionTarget> targets;

    public ArtifactAdoptionTargetRegistry(List<ArtifactAdoptionTarget> targets) {
        this.targets = targets.stream().collect(Collectors.toUnmodifiableMap(
                target -> target.targetKind().toUpperCase(Locale.ROOT), Function.identity()));
    }

    public ArtifactAdoptionTarget require(String targetKind) {
        ArtifactAdoptionTarget target = targetKind == null ? null
                : targets.get(targetKind.toUpperCase(Locale.ROOT));
        if (target == null) {
            throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(),
                    "ARTIFACT_ADOPTION_UNAVAILABLE", "当前目标暂不支持采用", targetKind);
        }
        return target;
    }

    public List<ArtifactAdoptionTarget.Capability> inspect(ArtifactAdoptionTarget.CapabilityContext context) {
        return targets.values().stream().map(target -> target.inspect(context)).filter(java.util.Objects::nonNull).toList();
    }
}
