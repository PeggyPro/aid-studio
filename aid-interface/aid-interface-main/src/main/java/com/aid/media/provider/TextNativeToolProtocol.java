package com.aid.media.provider;

import com.aid.media.constants.OpenAiCompatibleConstants;
import com.aid.tokendance.provider.common.TokenDanceProtocols;

/** Defines the provider request shape for supported native text-tool protocols. */
public final class TextNativeToolProtocol
{
    private static final String DEEPSEEK_CHAT_COMPLETIONS = "deepseek:chat-completions";

    private static final ProtocolContract TOKENDANCE_OPENAI_CHAT = new ProtocolContract(
            TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS, Shape.OPENAI_CHAT,
            TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS, ParallelControl.TOP_LEVEL_FALSE);
    private static final ProtocolContract GENERIC_OPENAI_CHAT = new ProtocolContract(
            OpenAiCompatibleConstants.PROTOCOL_TEXT, Shape.OPENAI_CHAT,
            TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS, ParallelControl.TOP_LEVEL_FALSE);
    private static final ProtocolContract DEEPSEEK_OPENAI_CHAT = new ProtocolContract(
            DEEPSEEK_CHAT_COMPLETIONS, Shape.OPENAI_CHAT,
            TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS, ParallelControl.OMIT);
    private static final ProtocolContract TOKENDANCE_OPENAI_RESPONSES = new ProtocolContract(
            TokenDanceProtocols.OPENAI_RESPONSES, Shape.OPENAI_RESPONSES,
            TokenDanceProtocols.OPENAI_RESPONSES, ParallelControl.TOP_LEVEL_FALSE);
    private static final ProtocolContract TOKENDANCE_ANTHROPIC = new ProtocolContract(
            TokenDanceProtocols.ANTHROPIC_MESSAGES, Shape.ANTHROPIC,
            TokenDanceProtocols.ANTHROPIC_MESSAGES, ParallelControl.TOOL_CHOICE_DISABLE);

    private TextNativeToolProtocol() { }

    public static Shape resolve(String protocol)
    {
        ProtocolContract contract = contract(protocol);
        return contract == null ? null : contract.wireShape();
    }

    public static boolean requiresStrictToolArguments(String protocol)
    {
        return DEEPSEEK_CHAT_COMPLETIONS.equals(protocol);
    }

    public static String canonicalProtocol(String protocol)
    {
        ProtocolContract contract = contract(protocol);
        return contract == null ? null : contract.messageProtocol();
    }

    /**
     * Resolves the provider identity, wire shape and optional parallel-tool control independently.
     * The legacy {@link #resolve(String)} and {@link #canonicalProtocol(String)} projections retain
     * their established values for persisted bindings and tool-message normalization.
     */
    public static ProtocolContract contract(String protocol)
    {
        if (TokenDanceProtocols.OPENAI_CHAT_COMPLETIONS.equals(protocol)) return TOKENDANCE_OPENAI_CHAT;
        if (DEEPSEEK_CHAT_COMPLETIONS.equals(protocol)) return DEEPSEEK_OPENAI_CHAT;
        if (OpenAiCompatibleConstants.PROTOCOL_TEXT.equalsIgnoreCase(protocol)) return GENERIC_OPENAI_CHAT;
        if (TokenDanceProtocols.OPENAI_RESPONSES.equals(protocol)) return TOKENDANCE_OPENAI_RESPONSES;
        if (TokenDanceProtocols.ANTHROPIC_MESSAGES.equals(protocol)) return TOKENDANCE_ANTHROPIC;
        return null;
    }

    public record ProtocolContract(String providerProtocol, Shape wireShape,
                                   String messageProtocol, ParallelControl parallelControl) { }

    public enum ParallelControl
    {
        TOP_LEVEL_FALSE,
        TOOL_CHOICE_DISABLE,
        OMIT
    }

    public enum Shape
    {
        OPENAI_CHAT,
        OPENAI_RESPONSES,
        ANTHROPIC
    }
}
