package com.aid.runtime.assistant;

import java.util.Optional;

/** Resolves one module to its explicitly activated, immutable main-Agent version. */
public interface AssistantModuleAgentResolver
{
    AssistantLoopBinding requireActive(String moduleCode);

    /**
     * Resolve an active binding for a read-only tool preparation. Deterministic binding,
     * package, model-readiness and executor rejections use a dedicated type; loader and
     * database failures retain their original exception so recovery remains conservative.
     */
    AssistantLoopBinding requireActiveForToolPreparation(String moduleCode);

    /**
     * Resolve one read-only capability snapshot without weakening execution admission.
     * A non-null binding has passed the active binding, frozen Agent and model-readiness checks;
     * executor readiness is reported separately because quote preparation and execution both
     * still require the unique registered protocol executor.
     */
    ActiveReadiness inspectActiveReadiness(String moduleCode);

    /**
     * Resolve one advisory active binding only when strict runtime executor admission also passes.
     * Missing or administratively unavailable bindings return empty; capability projection uses
     * inspectActiveReadiness so an executor failure retains its precise safe classification.
     */
    Optional<AssistantLoopBinding> findAvailableActive(String moduleCode);

    /** Resolve the immutable version pinned to an existing run, independent of later activation changes. */
    AssistantLoopBinding requirePinned(String moduleCode, Long agentVersionId, String packageDigest);

    /**
     * Resolve the immutable Agent used by planning preparation. Deterministic binding,
     * package, readiness and executor rejections are typed separately from loader and
     * database failures.
     */
    AssistantLoopBinding requirePinnedForPlanningPreparation(String moduleCode, Long agentVersionId,
                                                             String packageDigest);

    /**
     * Resolve the immutable Agent package used by the local read-resource tool. Deterministic
     * binding/package rejections are typed separately from loader, database and executor failures.
     */
    AssistantLoopBinding requirePinnedForPackageRead(String moduleCode, Long agentVersionId,
                                                     String packageDigest);

    boolean isAvailable(String moduleCode);

    final class PinnedBindingRejectedException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        public PinnedBindingRejectedException()
        {
            super("Pinned Assistant Agent binding rejected");
        }
    }

    final class ActiveBindingRejectedException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        public ActiveBindingRejectedException()
        {
            super("Active Assistant Agent binding rejected");
        }
    }

    record ActiveReadiness(AssistantLoopBinding binding, boolean executorAvailable)
    {
        public ActiveReadiness
        {
            if (binding == null && executorAvailable)
            {
                throw new IllegalArgumentException("Missing binding cannot have an executor");
            }
        }

        public static ActiveReadiness unavailable()
        {
            return new ActiveReadiness(null, false);
        }

        public boolean bindingAvailable()
        {
            return binding != null;
        }
    }
}
