package com.aid.canvas.config;

import com.aid.canvas.service.CanvasCollaborationService;
import com.aid.canvas.service.CanvasGenerationService;
import com.aid.canvas.service.impl.CanvasCollaborationServiceImpl;
import com.aid.generation.listener.GenerationConversationTaskListener;
import com.aid.runtime.service.GenericRuntimeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 画布独立维护调度与跨实例事件订阅，不启用应用内其他 @Scheduled 任务。 */
@Configuration
public class CanvasRuntimeConfig {
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler canvasTaskScheduler(@Value("${canvas.scheduling.pool-size:4}") int poolSize) {
        // 四个固定维护循环不得相互占用唯一线程，尤其不能饿死生成占用续期。
        if (poolSize < 4 || poolSize > 32) {
            throw new IllegalArgumentException("canvas.scheduling.pool-size 必须在 4 到 32 之间");
        }
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("canvas-maintenance-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    RedisMessageListenerContainer canvasRedisListener(RedisConnectionFactory factory,
                                                       CanvasCollaborationService collaborationService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((Message message, byte[] pattern) -> collaborationService.acceptClusterMessage(
                new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(CanvasCollaborationServiceImpl.EVENT_CHANNEL));
        return container;
    }

    @Component
    @RequiredArgsConstructor
    @ConditionalOnProperty(name = "canvas.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class CanvasMaintenanceJobs {
        private final ThreadPoolTaskScheduler canvasTaskScheduler;
        private final CanvasCollaborationService collaborationService;
        private final GenerationConversationTaskListener generationConversationTaskListener;
        private final GenericRuntimeService genericRuntimeService;

        @Value("${generation.conversation.reconcile-delay-ms:60000}")
        private long generationConversationReconcileDelayMs;

        @Value("${aid.runtime.recovery.delay-ms:30000}")
        private long runtimeRecoveryDelayMs;

        @PostConstruct
        void schedule() {
            canvasTaskScheduler.scheduleWithFixedDelay(collaborationService::cleanupIdleSessions, Duration.ofSeconds(15));
            canvasTaskScheduler.scheduleWithFixedDelay(generationConversationTaskListener::reconcilePending,
                    Duration.ofMillis(Math.max(1000L, generationConversationReconcileDelayMs)));
            canvasTaskScheduler.scheduleWithFixedDelay(genericRuntimeService::recover,
                    Duration.ofMillis(Math.max(1000L, runtimeRecoveryDelayMs)));
        }
    }
    @Component
    @RequiredArgsConstructor
    @ConditionalOnProperty(name = "canvas.task-reconciliation.enabled", havingValue = "true", matchIfMissing = true)
    static class CanvasTaskReconciliationJob {
        private final ThreadPoolTaskScheduler canvasTaskScheduler;
        private final CanvasGenerationService generationService;

        @PostConstruct
        void schedule() {
            canvasTaskScheduler.scheduleWithFixedDelay(generationService::reconcileBoundTasks, Duration.ofSeconds(30));
        }
    }
}
