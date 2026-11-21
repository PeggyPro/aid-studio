package com.aid.assistant.effect;

/** Optional product adapter for extracting a target from a verified adoption receipt. */
public interface AssistantArtifactAdoptionEffectProjector {
    String targetKind();

    AssistantTargetEffectProjection project(VerifiedArtifactAdoptionEffect effect);
}
