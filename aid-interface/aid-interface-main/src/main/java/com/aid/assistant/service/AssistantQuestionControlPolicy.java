package com.aid.assistant.service;

import com.aid.assistant.dto.AssistantRequests.Question;
import com.aid.assistant.dto.AssistantRequests.QuestionOption;
import com.aid.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Only a selected, persisted control option can pause a run; labels and free text are data. */
@Slf4j
public final class AssistantQuestionControlPolicy {
    public static final String CONTINUE_RUN = "CONTINUE_RUN";
    public static final String PAUSE_RUN = "PAUSE_RUN";
    public static final String PLATFORM_PAUSE_OPTION_ID = "platform_pause_run";
    public static final String PLATFORM_PAUSE_LABEL = "先暂停，暂不生成";
    public static final String PLATFORM_PAUSE_DESCRIPTION = "保持当前进度，只有明确恢复后才继续。";

    private AssistantQuestionControlPolicy() { }

    /** Appends the only trusted pause option before the persisted question digest is calculated. */
    public static List<Question> normalizeForPersistence(List<Question> questions) {
        if (questions == null) return null;
        for (Question question : questions) {
            if (question == null || "TEXT".equals(question.getType()) || question.getOptions() == null) continue;
            List<QuestionOption> options = new ArrayList<>(question.getOptions());
            int platformPauseOptions = 0;
            for (QuestionOption option : options) {
                if (option == null) continue;
                boolean reservedId = PLATFORM_PAUSE_OPTION_ID.equals(option.getOptionId());
                boolean pauseAction = PAUSE_RUN.equals(option.getControlAction());
                if (reservedId || pauseAction) {
                    if (!isPlatformPauseOption(option)) fail();
                    platformPauseOptions++;
                    if (platformPauseOptions != 1) fail();
                }
            }
            if (platformPauseOptions == 0) {
                if (options.size() >= 100) fail();
                options.add(platformPauseOption());
            }
            question.setOptions(options);
        }
        return questions;
    }

    public static boolean isPlatformPauseOption(QuestionOption option) {
        return option != null
                && PLATFORM_PAUSE_OPTION_ID.equals(option.getOptionId())
                && PLATFORM_PAUSE_LABEL.equals(option.getLabel())
                && PLATFORM_PAUSE_DESCRIPTION.equals(option.getDescription())
                && option.isExclusive()
                && PAUSE_RUN.equals(option.getControlAction());
    }

    public static boolean requestsPause(List<Question> questions, Map<String, List<String>> choices) {
        return questions.stream().anyMatch(question -> requestsPause(question,
                choices.getOrDefault(question.getQuestionId(), List.of())));
    }

    public static boolean requestsPause(Question question, List<String> choices) {
        return question != null && question.getOptions() != null && choices != null
                && question.getOptions().stream().anyMatch(option -> option != null
                && PAUSE_RUN.equals(option.getControlAction()) && choices.contains(option.getOptionId()));
    }

    private static QuestionOption platformPauseOption() {
        QuestionOption option = new QuestionOption();
        option.setOptionId(PLATFORM_PAUSE_OPTION_ID);
        option.setLabel(PLATFORM_PAUSE_LABEL);
        option.setDescription(PLATFORM_PAUSE_DESCRIPTION);
        option.setExclusive(true);
        option.setControlAction(PAUSE_RUN);
        return option;
    }

    private static void fail() {
        log.info("助手问题暂停选项无效");
        throw new ServiceException("问题选项无效");
    }
}
