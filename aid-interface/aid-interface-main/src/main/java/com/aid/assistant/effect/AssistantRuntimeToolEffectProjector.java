package com.aid.assistant.effect;

import java.util.Set;

/** Optional product adapter for extracting a target only from a verified Tool receipt. */
public interface AssistantRuntimeToolEffectProjector {
    Set<String> supportedToolCodes();

    AssistantTargetEffectProjection project(VerifiedRuntimeToolEffect effect);
}
