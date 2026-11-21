package com.aid.runtime.assistant;

/** Optional business module registration. Registration does not grant tools or bypass fixed Agent bindings. */
public interface AssistantModuleExtension
{
    String moduleCode();
    String agentCode();
    default boolean supportsTransport(String transport) { return true; }
    default boolean supportsStreaming(String transport) { return false; }
    default String realtimeWebSocketPath(String transport) { return null; }
    default boolean supportsConversationBudget() { return false; }
    default boolean automaticExecutionRequired() { return false; }
    default boolean usesExecutionBudget() { return true; }
    /** Feature-specific tools that may be absent while the rest of this module remains usable. */
    default java.util.Set<String> optionalDiscoveryToolCodes() { return java.util.Set.of(); }
    default AssistantLoopBinding constrainActiveBinding(AssistantLoopBinding binding) { return binding; }
    /** Retain verified business receipts across plan revisions, separate from plan dependencies. */
    default java.util.Set<String> retainedReceiptToolCodes() { return java.util.Set.of(); }
}
