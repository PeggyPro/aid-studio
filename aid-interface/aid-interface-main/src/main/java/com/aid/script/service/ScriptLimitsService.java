package com.aid.script.service;

import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.aid.aid.service.IAidConfigService;
import com.aid.common.exception.ServiceException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 为公共配置和剧本写入提供同一组剧集字数限制。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScriptLimitsService {
    public static final int MOVIE_MAX_CHARS = 10_000;
    public static final int MAX_CONFIGURED_CHARS = 1_000_000;
    private static final String CATEGORY = "script";
    private static final Pattern LINE_BREAK = Pattern.compile("(?i)</?br\\s*/?>");
    private static final Pattern LETTER_OR_NUMBER = Pattern.compile("[\\p{L}\\p{N}]");

    private final IAidConfigService configService;

    public Limits current() {
        return new Limits(read("script_series_max_chars", 100_000),
                read("script_episode_max_chars", 10_000), MOVIE_MAX_CHARS);
    }

    private int read(String key, int defaultValue) {
        String value = configService.getConfigValue(CATEGORY, key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0 && parsed <= MAX_CONFIGURED_CHARS) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 非法历史配置使用程序默认值。
        }
        log.warn("剧本字数配置无效, key={}", key);
        return defaultValue;
    }

    /** 中文、字母和数字按 Unicode 字符计数，换行标签、空白和标点不计。 */
    public static int countChars(String text) {
        if (text == null) {
            return 0;
        }
        return (int) LETTER_OR_NUMBER.matcher(LINE_BREAK.matcher(text).replaceAll("")).results().count();
    }

    public static void validate(String text, int maxChars, String label) {
        if (countChars(text) > maxChars) {
            throw new ServiceException(label + "超过" + maxChars + "字");
        }
    }

    public record Limits(int seriesMaxChars, int episodeMaxChars, int movieMaxChars) {
    }
}
