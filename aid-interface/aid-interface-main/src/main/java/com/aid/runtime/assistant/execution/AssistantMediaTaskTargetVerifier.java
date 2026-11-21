package com.aid.runtime.assistant.execution;

import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;

import java.util.Set;

/** Optional private target persistence check after real media archival, before a child is successful. */
public interface AssistantMediaTaskTargetVerifier {
    Set<String> supportedToolCodes();
    boolean isTargetPersisted(AidRuntimeRun run, AidRuntimeRunStep step, Long mediaTaskId);
}
