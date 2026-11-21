package com.aid.assistant.creative.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.creative.dto.CreativeRequests;
import com.aid.assistant.creative.service.CreativePlanningService;
import com.aid.assistant.creative.vo.CreativeViews;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic planning arithmetic. The main Agent decides whether and when to use these hints. */
@Service
public class CreativePlanningServiceImpl implements CreativePlanningService {
    private static final Set<String> MODES = Set.of("CRAFT", "ADAPT", "REVIEW");

    @Override
    public CreativeViews.Strategy plan(CreativeRequests.Strategy request) {
        if (request == null || request.getTargetWordCount() == null
                || request.getTargetWordCount() < 1 || request.getTargetWordCount() > 10_000_000) {
            throw new IllegalArgumentException("Invalid targetWordCount");
        }
        String mode = StrUtil.isBlank(request.getMode()) ? "CRAFT"
                : request.getMode().trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(mode)) throw new IllegalArgumentException("Invalid creative mode");
        String format = StrUtil.isBlank(request.getTargetFormat()) ? "LONGFORM_TEXT"
                : request.getTargetFormat().trim().toUpperCase(Locale.ROOT);
        if (format.length() > 40) throw new IllegalArgumentException("Invalid targetFormat");
        int episodes = request.getEpisodeCount() == null ? 1 : request.getEpisodeCount();
        if (episodes < 1 || episodes > 10000) throw new IllegalArgumentException("Invalid episodeCount");

        int base = request.getTargetWordCount() / episodes;
        int remainder = request.getTargetWordCount() % episodes;
        int cursor = 0;
        List<CreativeViews.EpisodeRange> ranges = new ArrayList<>();
        for (int number = 1; number <= episodes; number++) {
            int words = base + (number <= remainder ? 1 : 0);
            ranges.add(new CreativeViews.EpisodeRange(number, words, cursor, cursor + words));
            cursor += words;
        }
        List<String> hints = new ArrayList<>();
        if ("ADAPT".equals(mode) && request.getSourceCharCount() != null && request.getSourceCharCount() > 0) {
            hints.add("Verify source chunk coverage before claiming the adaptation is complete.");
        }
        if (episodes > 1) hints.add("Review continuity at episode boundaries selected by the Agent.");
        if (request.getTargetWordCount() >= 50000) hints.add("Use immutable intermediate revisions and resumable checkpoints.");
        if ("REVIEW".equals(mode)) hints.add("Attach findings to exact artifact and source locators.");

        CreativeViews.Strategy result = new CreativeViews.Strategy();
        result.setMode(mode);
        result.setTargetFormat(format);
        result.setTargetWordCount(request.getTargetWordCount());
        result.setEpisodeCount(episodes);
        result.setWordsPerEpisode(base);
        result.setEpisodeRanges(ranges);
        result.setCheckpoints(hints);
        return result;
    }
}
