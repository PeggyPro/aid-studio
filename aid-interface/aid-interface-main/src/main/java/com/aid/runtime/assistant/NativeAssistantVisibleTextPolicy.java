package com.aid.runtime.assistant;

import com.aid.assistant.service.AssistantControlTextPresentation;

/** Native presentation delegates to the same policy used by persisted conversation history. */
public final class NativeAssistantVisibleTextPolicy
{
    private final AssistantControlTextPresentation delegate = new AssistantControlTextPresentation();

    public String accept(String text) { return delegate.accept(text); }
    public String finish() { return delegate.finish(); }
    public boolean blocked() { return delegate.blocked(); }

    public static Inspection inspect(String text)
    {
        var result = AssistantControlTextPresentation.inspect(text);
        return new Inspection(result.visible(), result.blocked());
    }

    public record Inspection(String visible, boolean blocked) { }
}
