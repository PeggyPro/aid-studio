package com.aid.assistant.creative.series;

import com.aid.assistant.error.AssistantServiceException;
import com.aid.script.helper.ScriptEpisodeSplitter;
import com.aid.script.helper.ScriptEpisodeSplitter.EpisodeSegment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic policy shared by series capability preview and atomic APPEND commit. */
@Component
public class SeriesScriptSplitPolicy {
    public static final String CONTRACT_VERSION = "script-episode-splitter/v1";
    public static final int MAX_TOTAL_EFFECTIVE_CHARS = 100_000;
    public static final int MAX_EPISODE_EFFECTIVE_CHARS = 10_000;
    public static final int MAX_EPISODES = 200;
    public static final int MAX_KEYWORD_CHARS = 100;
    private static final int TITLE_MAX_CHARS = 50;
    private static final int DESCRIPTION_CHARS = 20;
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][a-zA-Z0-9]*(\\s[^<>]*)?/?>");
    private static final Pattern BR_TAG = Pattern.compile("(?i)</?br\\s*/?>");

    public SplitPlan split(String text, String episodeKeyword) {
        if (!StringUtils.hasText(text)) {
            fail("SERIES_SCRIPT_REQUIRED", "Series screenplay content is required", null);
        }
        String normalizedKeyword = normalizeKeyword(episodeKeyword);
        String withoutBreaks = BR_TAG.matcher(text).replaceAll("");
        if (HTML_TAG.matcher(withoutBreaks).find()) {
            fail("SERIES_SCRIPT_HTML_UNSUPPORTED", "Only line-break HTML tags are supported", null);
        }
        int total = effectiveChars(text);
        if (total > MAX_TOTAL_EFFECTIVE_CHARS) {
            fail("SERIES_SCRIPT_TOO_LARGE", "Series screenplay exceeds the effective character limit", total);
        }
        List<EpisodeSegment> raw = ScriptEpisodeSplitter.split(text, normalizedKeyword);
        if (raw.isEmpty()) {
            fail("SERIES_SPLIT_NOT_FOUND", "No episode delimiters were found", normalizedKeyword);
        }
        if (raw.size() > MAX_EPISODES) {
            fail("SERIES_SPLIT_TOO_MANY_EPISODES", "Series split exceeds 200 episodes", raw.size());
        }
        List<Segment> segments = new ArrayList<>(raw.size());
        Set<Integer> sequence = new HashSet<>();
        int previousEnd = 0;
        for (EpisodeSegment value : raw) {
            int count = effectiveChars(value.getContent());
            if (count < 1) {
                fail("SERIES_EPISODE_EMPTY", "An episode screenplay is empty", value.getSequenceNo());
            }
            if (count > MAX_EPISODE_EFFECTIVE_CHARS) {
                fail("SERIES_EPISODE_TOO_LARGE", "An episode screenplay exceeds 10000 effective characters",
                        value.getSequenceNo());
            }
            if (!sequence.add(value.getSequenceNo()) || value.getSequenceNo() != segments.size() + 1) {
                fail("SERIES_SPLIT_SEQUENCE_INVALID", "Episode sequence is not unique and contiguous",
                        value.getSequenceNo());
            }
            if (value.getSourceCharStart() < 0 || value.getSourceCharEnd() <= value.getSourceCharStart()
                    || value.getSourceCharEnd() > text.length() || value.getSourceCharStart() < previousEnd) {
                fail("SERIES_SPLIT_RANGE_INVALID", "Episode source envelope is invalid", value.getSequenceNo());
            }
            previousEnd = value.getSourceCharEnd();
            String title = resolveTitle(value);
            String description = ScriptEpisodeSplitter.summarize(value.getContent(), DESCRIPTION_CHARS);
            segments.add(new Segment(value.getSequenceNo(), title, description, value.getContent(),
                    SeriesAdoptionDigest.sha256Text(value.getContent()), value.getSourceCharStart(),
                    value.getSourceCharEnd(), count));
        }
        return new SplitPlan(normalizedKeyword, total, List.copyOf(segments));
    }

    public int validateEpisodeContent(String text) {
        if (!StringUtils.hasText(text)) {
            fail("SERIES_EPISODE_EMPTY", "An episode screenplay is empty", null);
        }
        String withoutBreaks = BR_TAG.matcher(text).replaceAll("");
        if (HTML_TAG.matcher(withoutBreaks).find()) {
            fail("SERIES_SCRIPT_HTML_UNSUPPORTED", "Only line-break HTML tags are supported", null);
        }
        int count = effectiveChars(text);
        if (count > MAX_EPISODE_EFFECTIVE_CHARS) {
            fail("SERIES_EPISODE_TOO_LARGE", "An episode screenplay exceeds 10000 effective characters", count);
        }
        return count;
    }

    public String normalizeKeyword(String keyword) {
        if (!StringUtils.hasText(keyword)) return null;
        String normalized = keyword.trim();
        if (normalized.length() > MAX_KEYWORD_CHARS) {
            fail("SERIES_SPLIT_KEYWORD_TOO_LARGE", "Episode delimiter exceeds 100 characters", null);
        }
        return normalized;
    }

    public int effectiveChars(String text) {
        if (!StringUtils.hasText(text)) return 0;
        return BR_TAG.matcher(text).replaceAll("").replaceAll("\\s+", "").length();
    }

    private String resolveTitle(EpisodeSegment segment) {
        String title = segment.getTitle();
        if (!StringUtils.hasText(title)) return "Episode " + segment.getSequenceNo();
        String trimmed = title.trim();
        return trimmed.length() <= TITLE_MAX_CHARS ? trimmed : trimmed.substring(0, TITLE_MAX_CHARS);
    }

    private void fail(String code, String message, Object details) {
        throw new AssistantServiceException(HttpStatus.UNPROCESSABLE_ENTITY.value(), code, message, details);
    }

    public record SplitPlan(String episodeKeyword, int totalEffectiveChars, List<Segment> segments) {
    }

    public record Segment(int sequenceNo, String title, String description, String content,
                          String contentHash, int sourceCharStart, int sourceCharEnd,
                          int effectiveCharCount) {
    }
}
