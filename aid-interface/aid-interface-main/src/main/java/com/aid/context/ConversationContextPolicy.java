package com.aid.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Consumer-independent selection of already authorized conversation sources.
 * Persistence and live resource authorization belong to the source adapter;
 * omission from this projection never authorizes deletion of a source.
 */
public final class ConversationContextPolicy {
    private ConversationContextPolicy() { }

    public enum Kind { USER, CONFIRMED_INPUT, ASSISTANT, TOOL }

    /** sequence is stable within the adapter's verified conversation scope. */
    public record Source(String sourceId, long sequence, Kind kind, String text) {
        public Source {
            if (sourceId == null || sourceId.isBlank() || sequence < 0 || kind == null || text == null)
                throw new IllegalArgumentException("CONTEXT_SOURCE_INVALID");
        }

        public boolean mandatory() {
            return kind == Kind.USER || kind == Kind.CONFIRMED_INPUT;
        }
    }

    /** Text bytes exclude the enclosing wire format, which the caller must also budget. */
    public record Selection(List<Source> included, List<String> omittedSourceIds, long selectedTextBytes) {
        public Selection {
            included = List.copyOf(included);
            omittedSourceIds = List.copyOf(omittedSourceIds);
        }
    }

    /**
     * Keep user instructions and accepted answers verbatim. Retain explicit dependencies,
     * then recent optional prose within the caller's text allowance. Older optional sources
     * remain addressable; neither lexical heuristics nor Skill names determine importance.
     */
    public static Selection select(List<Source> sources, Set<String> requiredSourceIds,
                                   int recentOptionalCount, long textByteBudget) {
        if (sources == null || requiredSourceIds == null || recentOptionalCount < 0 || textByteBudget < 0)
            throw new IllegalArgumentException("CONTEXT_SELECTION_INVALID");
        var ordered = new ArrayList<>(sources);
        if (ordered.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("CONTEXT_SOURCE_INVALID");
        ordered.sort(Comparator.comparingLong(Source::sequence).thenComparing(Source::sourceId));
        var identities = new LinkedHashMap<String, Source>();
        for (Source source : ordered) {
            if (identities.putIfAbsent(source.sourceId(), source) != null)
                throw new IllegalArgumentException("CONTEXT_SOURCE_IDENTITY_COLLISION");
        }
        if (!identities.keySet().containsAll(requiredSourceIds))
            throw new IllegalArgumentException("CONTEXT_REQUIRED_SOURCE_UNAVAILABLE");

        Set<String> selected = new HashSet<>(requiredSourceIds);
        for (Source source : ordered) if (source.mandatory()) selected.add(source.sourceId());
        long used = 0;
        for (Source source : ordered) {
            if (!selected.contains(source.sourceId())) continue;
            long size = utf8Bytes(source);
            if (size > textByteBudget - used)
                throw com.aid.common.error.TaskErrorPresentation.fromCode(
                        com.aid.common.error.TaskErrorCode.USER_INPUT_TOO_LONG,
                        "必要原文超过上下文预算，请分段处理；原始对话已保留");
            used += size;
        }
        int optional = 0;
        for (int i = ordered.size() - 1; i >= 0 && optional < recentOptionalCount; i--) {
            Source source = ordered.get(i);
            if (selected.contains(source.sourceId())) continue;
            long size = utf8Bytes(source);
            if (size > textByteBudget - used) continue;
            selected.add(source.sourceId());
            used += size;
            optional++;
        }
        List<Source> included = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        for (Source source : ordered) {
            if (selected.contains(source.sourceId())) included.add(source);
            else omitted.add(source.sourceId());
        }
        return new Selection(included, omitted, used);
    }

    private static int utf8Bytes(Source source) {
        return TextContentMetrics.measure(source.text()).utf8Bytes();
    }
}
