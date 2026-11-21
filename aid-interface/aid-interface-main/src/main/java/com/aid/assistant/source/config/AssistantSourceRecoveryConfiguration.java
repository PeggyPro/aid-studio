package com.aid.assistant.source.config;

import com.aid.assistant.source.service.AssistantSourceImportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

/** Recover free document parsing without enabling unrelated application scheduled jobs. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "aid.assistant.source.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class AssistantSourceRecoveryConfiguration {
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler assistantSourceRecoveryScheduler(
            AssistantSourceImportService imports,
            @Value("${aid.assistant.source.recovery-delay-ms:15000}") long delayMillis) {
        if (delayMillis < 1000 || delayMillis > 3600000) {
            throw new IllegalArgumentException("aid.assistant.source.recovery-delay-ms must be between 1000 and 3600000");
        }
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("assistant-source-recovery-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(imports::recoverExpiredImports, Duration.ofMillis(delayMillis));
        return scheduler;
    }
}
