package com.aid.assistant.source.service;

import com.aid.assistant.error.AssistantServiceException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 限制文档解析的并发与等待时间。 */
@Component
public final class SourceParserExecutor {
    private static final int TIMEOUT_SECONDS = 30;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "assistant-source-parser");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public <T> T parse(Callable<T> parser) {
        Future<T> future;
        try { future = executor.submit(parser); }
        catch (RejectedExecutionException busy) {
            throw new AssistantServiceException(503, "SOURCE_PARSER_BUSY", "解析繁忙请重试", null);
        }
        try { return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AssistantServiceException(503, "SOURCE_PARSER_INTERRUPTED", "解析已中断", null);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new AssistantServiceException(422, "SOURCE_PARSER_TIMEOUT", "解析超时请重试", null);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof AssistantServiceException known) throw known;
            throw new AssistantServiceException(422, "SOURCE_PARSER_FAILED", "文档解析失败", null);
        }
    }

    @PreDestroy public void close() { executor.shutdownNow(); }
}
