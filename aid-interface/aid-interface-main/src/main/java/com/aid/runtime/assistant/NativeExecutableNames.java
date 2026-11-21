package com.aid.runtime.assistant;

import java.util.Locale;

/** Host-generated labels; the complete binding digest remains part of every executable name. */
final class NativeExecutableNames {
    static final String VERSION = "descriptive-executable-names/v1";
    private NativeExecutableNames() { }

    static String name(String action, String code, String step, String legacy, boolean descriptive) {
        if (!descriptive) return legacy;
        String prefix = "SKILL".equals(action) ? "skill_" : "tool_";
        if (legacy == null || !legacy.matches(prefix + "[a-f0-9]{32}"))
            throw new IllegalArgumentException("Invalid executable binding digest");
        // Keep all 128 bits of the existing identifier. Labels alone never authorize a call.
        return prefix + label(code, 14, true) + "_" + label(step, 64 - prefix.length() - 48, false)
                + "_" + legacy.substring(prefix.length());
    }

    private static String label(String value, int limit, boolean tail) {
        String text = value == null ? "step" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (text.isEmpty()) text = "step";
        if (text.length() <= limit) return text;
        return tail ? text.substring(text.length() - limit) : text.substring(0, limit);
    }
}
