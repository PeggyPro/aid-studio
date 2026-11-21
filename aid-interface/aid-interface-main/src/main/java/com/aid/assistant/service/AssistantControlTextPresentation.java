package com.aid.assistant.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Read-only presentation of model control text; never an invocation parser. */
public final class AssistantControlTextPresentation
{
    private static final int MAX_TAG_CHARS = 256;
    private static final String NOTICE = "\n\n这段回复包含格式异常内容，不能据此确认操作已执行。";
    private static final Pattern CONTROL = Pattern.compile("<invoke\\s+name\\s*=\\s*[\"']"
            + "(?:tool_[0-9a-f]{32}|skill_[0-9a-f]{32}|assistant_(?:propose_plan|ask_user|checkpoint|complete))"
            + "[\"']\\s*>");
    private final StringBuilder tag = new StringBuilder();
    private char delimiter;
    private int delimiterSize;
    private char marker;
    private int markerSize;
    private boolean escaped;
    private boolean blocked;

    public String accept(String text)
    {
        if (blocked || text == null || text.isEmpty()) return "";
        StringBuilder visible = new StringBuilder(text.length());
        for (int i = 0; i < text.length() && !blocked; i++)
        {
            char c = text.charAt(i);
            if (tag.length() > 0)
            {
                if (c == '<' || tag.length() < "<invoke".length()
                        && !"<invoke".startsWith(tag.toString() + c))
                {
                    visible.append(tag);
                    tag.setLength(0);
                }
                else
                {
                    tag.append(c);
                    if (c == '>' || tag.length() >= MAX_TAG_CHARS)
                    {
                        if (CONTROL.matcher(tag).matches()) blocked = true;
                        else visible.append(tag);
                        tag.setLength(0);
                    }
                    continue;
                }
            }
            if (markerSize > 0 && c != marker) settleMarker();
            if ((c == '`' || c == '~') && !escaped)
            {
                if (markerSize == 0) marker = c;
                markerSize++;
                visible.append(c);
                continue;
            }
            if (c == '<' && delimiter == 0 && !escaped)
            {
                tag.append(c);
                continue;
            }
            visible.append(c);
            escaped = c == '\\' && !escaped;
        }
        return visible.toString();
    }

    private void settleMarker()
    {
        if (delimiter == 0 && (marker == '`' || markerSize >= 3))
        {
            delimiter = marker;
            delimiterSize = markerSize;
        }
        else if (delimiter == marker && (markerSize == delimiterSize
                || delimiterSize >= 3 && markerSize > delimiterSize))
        {
            delimiter = 0;
            delimiterSize = 0;
        }
        markerSize = 0;
    }

    public String finish()
    {
        String remaining = blocked ? "" : tag.toString();
        tag.setLength(0);
        return remaining;
    }

    public boolean blocked() { return blocked; }

    public static Inspection inspect(String text)
    {
        AssistantControlTextPresentation policy = new AssistantControlTextPresentation();
        String visible = policy.accept(text) + policy.finish();
        return new Inspection(visible, policy.blocked());
    }

    public static String projectText(String role, String messageKind, String text)
    {
        if (!assistantOutput(role, messageKind) || text == null) return text;
        Inspection result = inspect(text);
        return result.blocked() ? result.visible() + NOTICE : text;
    }

    public static List<String> projectParts(String role, String messageKind, List<String> parts)
    {
        if (!assistantOutput(role, messageKind) || parts == null || parts.isEmpty()) return parts;
        AssistantControlTextPresentation policy = new AssistantControlTextPresentation();
        List<String> projected = new ArrayList<>(parts.size());
        for (String part : parts) projected.add(part == null ? null : policy.accept(part));
        if (!policy.blocked()) return parts;
        int last = projected.size() - 1;
        projected.set(last, (projected.get(last) == null ? "" : projected.get(last)) + NOTICE);
        return projected;
    }

    private static boolean assistantOutput(String role, String kind)
    {
        return "ASSISTANT".equals(role) && "ASSISTANT_OUTPUT".equals(kind);
    }

    public record Inspection(String visible, boolean blocked) { }
}
