package com.aid.runtime.assistant;

/** The returned model envelope does not match the Agent's published output contract. */
final class AssistantOutputContractException extends IllegalArgumentException {
    static final String CODE = "AGENT_OUTPUT_CONTRACT_INVALID";

    AssistantOutputContractException() {
        super(CODE);
    }
}
