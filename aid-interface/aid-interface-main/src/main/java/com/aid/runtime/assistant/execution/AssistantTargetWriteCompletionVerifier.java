package com.aid.runtime.assistant.execution;

import com.aid.assistant.effect.VerifiedRuntimeToolEffect;

import java.util.Set;
import java.util.List;

/** Verifies a receipt-proven technical target write without asserting creative quality. */
public interface AssistantTargetWriteCompletionVerifier {
    Set<String> supportedToolCodes();
    void requireCurrentWrite(VerifiedRuntimeToolEffect effect);
    /** Default providers retain strict per-write current-state checks. */
    default void requireCompletedWrites(List<VerifiedRuntimeToolEffect> effects) {
        effects.forEach(this::requireCurrentWrite);
    }
}
