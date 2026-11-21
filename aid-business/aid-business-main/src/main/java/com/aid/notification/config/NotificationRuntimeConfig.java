package com.aid.notification.config;

import com.aid.notification.service.NotificationRealtimeService;
import com.aid.notification.service.NotificationService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 配置通知定时发布、权威同步与跨实例订阅。 */
@Configuration
public class NotificationRuntimeConfig
{
    @Bean(name = "notificationTaskScheduler", destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "notification.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    ThreadPoolTaskScheduler notificationTaskScheduler()
    {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("notification-maintenance-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.initialize();
        return scheduler;
    }

    @Bean(name = "notificationRedisListener", destroyMethod = "stop")
    RedisMessageListenerContainer notificationRedisListener(RedisConnectionFactory factory,
                                                             NotificationRealtimeService realtimeService,
                                                             @Qualifier("notificationRedisSignalExecutor") ExecutorService signalExecutor,
                                                             @Qualifier("notificationRedisSubscriptionExecutor") ExecutorService subscriptionExecutor)
    {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.setTaskExecutor(signalExecutor);
        container.setSubscriptionExecutor(subscriptionExecutor);
        container.addMessageListener((Message message, byte[] pattern) -> realtimeService.acceptClusterSignal(
                        new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(realtimeService.signalChannel()));
        return container;
    }

    @Bean(name = "notificationRedisSignalExecutor", destroyMethod = "shutdown")
    ExecutorService notificationRedisSignalExecutor()
    {
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(512), runnable ->
                {
                    Thread thread = new Thread(runnable, "notification-redis-signal");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.DiscardPolicy());
    }

    @Bean(name = "notificationRedisSubscriptionExecutor", destroyMethod = "shutdown")
    ExecutorService notificationRedisSubscriptionExecutor()
    {
        return Executors.newSingleThreadExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "notification-redis-subscription");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Slf4j
    @Component
    @ConditionalOnProperty(name = "notification.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class NotificationMaintenanceJobs
    {
        @Qualifier("notificationTaskScheduler")
        private final ThreadPoolTaskScheduler scheduler;
        private final NotificationService notificationService;
        private final NotificationRealtimeService realtimeService;

        @Value("${notification.scheduled.delay-ms:5000}")
        private long scheduledDelayMs;

        @Value("${notification.realtime.sync-delay-ms:30000}")
        private long syncDelayMs;

        NotificationMaintenanceJobs(@Qualifier("notificationTaskScheduler") ThreadPoolTaskScheduler scheduler,
                                    NotificationService notificationService,
                                    NotificationRealtimeService realtimeService)
        {
            this.scheduler = scheduler;
            this.notificationService = notificationService;
            this.realtimeService = realtimeService;
        }

        @PostConstruct
        void schedule()
        {
            scheduler.scheduleWithFixedDelay(() -> safeRun(notificationService::publishDueScheduled,
                            "定时通知发布"), Duration.ofMillis(Math.max(1000L, scheduledDelayMs)));
            scheduler.scheduleWithFixedDelay(() -> safeRun(realtimeService::synchronizeOnlineUsers,
                            "通知状态同步"), Duration.ofMillis(Math.max(10000L, syncDelayMs)));
            scheduler.scheduleWithFixedDelay(() -> safeRun(realtimeService::cleanupIdleConnections,
                            "通知连接清理"), Duration.ofSeconds(20));
        }

        private void safeRun(Runnable action, String name)
        {
            try
            {
                action.run();
            }
            catch (RuntimeException error)
            {
                log.error("{}失败", name, error);
            }
        }
    }
}
