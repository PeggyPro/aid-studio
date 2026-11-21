package com.aid.media.service;

import com.aid.common.exception.ServiceException;
import com.aid.common.error.TaskErrorCode;
import com.aid.common.error.TaskErrorResult;
import com.aid.common.error.TaskErrorSnapshot;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.provider.ProviderResponseHelper;
import com.aid.media.provider.ProviderSubmissionFailures;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.TextTransportFailure;
import com.aid.media.provider.TextFailureBillingPolicy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime health only. Existing tasks, administrative enablement and billing are owned elsewhere. */
@Slf4j
@Service
public class MediaProviderRouteHealth {
    private static final long PROBE_LEASE_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long STATE_TTL_MS = TimeUnit.HOURS.toMillis(24);
    private static final int MONITOR_LIMIT = 20;
    private static final DefaultRedisScript<String> ADMIT = script("media-route-admit.lua", String.class);
    private static final DefaultRedisScript<Long> OBSERVE = script("media-route-observe.lua", Long.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final String failureWindowMs;
    private final String cooldownMs;
    private final String failureThreshold;
    private final AtomicLong nextRedisWarning = new AtomicLong();

    public MediaProviderRouteHealth(StringRedisTemplate redis,
            @Value("${aid.media.route-health.enabled:true}") boolean enabled,
            @Value("${aid.media.route-health.failure-threshold:3}") int failureThreshold,
            @Value("${aid.media.route-health.failure-window-seconds:60}") int failureWindowSeconds,
            @Value("${aid.media.route-health.initial-cooldown-seconds:30}") int cooldownSeconds) {
        if (failureThreshold < 2 || failureThreshold > 20 || failureWindowSeconds < 10
                || failureWindowSeconds > 600 || cooldownSeconds < 5 || cooldownSeconds > 300) {
            throw new IllegalArgumentException("Invalid provider route health settings");
        }
        this.redis = redis;
        this.enabled = enabled;
        this.failureThreshold = Integer.toString(failureThreshold);
        this.failureWindowMs = millis(failureWindowSeconds);
        this.cooldownMs = millis(cooldownSeconds);
    }

    public Attempt admit(AiModelConfigVo config) {
        if (!enabled || config.getProviderId() == null || blank(config.getModelCode())
                || blank(config.getBaseUrl())) return Attempt.NONE;
        String modelKey = modelKey(config.getModelCode());
        String route = modelKey + ":route:" + fingerprint(config);
        String admission;
        try {
            admission = redis.execute(ADMIT, List.of(route), UUID.randomUUID().toString(),
                    Long.toString(PROBE_LEASE_MS));
        } catch (RuntimeException failure) {
            warnRedis(failure);
            return Attempt.NONE;
        }
        if ("blocked".equals(admission)) {
            ServiceException blocked = ProviderSubmissionFailures.notSent("上游渠道暂不可用，稍后重试");
            blocked.setTaskErrorJson(TextFailureBillingPolicy.mergeErrorSnapshot(blocked.getTaskErrorJson(),
                    TaskErrorResult.of(TaskErrorCode.PROVIDER_BUSY)));
            throw blocked;
        }
        return admission == null ? Attempt.NONE : new Attempt(route, modelKey, admission);
    }

    public void response(Attempt attempt, ProviderSubmitResult result) {
        if (result == null) {
            abandoned(attempt);
        } else if (accepted(result)) {
            observe(attempt, "healthy", "");
        } else if (confirmedModelUnavailable(result)) {
            abandoned(attempt);
        } else {
            failed(attempt, null, result.getErrorDetailJson(), result.getSubmissionHttpStatus());
        }
    }

    private static boolean confirmedModelUnavailable(ProviderSubmitResult result) {
        if (result.getConfirmedFailureCode() == null) return false;
        var error = TaskErrorSnapshot.read(result.getErrorDetailJson());
        return error != null && TaskErrorCode.UPSTREAM_MODEL_UNAVAILABLE.name().equals(error.getErrorCode());
    }

    public void streamFinished(Attempt attempt, boolean completed, String snapshot) {
        if (completed) observe(attempt, "healthy", "");
        else failed(attempt, null, snapshot, null);
    }

    public void failed(Attempt attempt, Throwable failure, String snapshot) {
        failed(attempt, failure, snapshot, null);
    }

    private void failed(Attempt attempt, Throwable failure, String snapshot, Integer httpStatus) {
        if (attempt == Attempt.NONE) return;
        if (failure instanceof ServiceException service && snapshot == null) snapshot = service.getTaskErrorJson();
        var error = ProviderResponseHelper.readTree(snapshot);
        if (httpStatus == null && error != null && error.path("providerHttpStatus").isInt()) {
            httpStatus = error.path("providerHttpStatus").intValue();
        }
        if (httpStatus != null && List.of(500, 502, 503, 504).contains(httpStatus)) {
            observe(attempt, "retryable", "HTTP_" + httpStatus);
        } else if (failure instanceof TextTransportFailure) {
            observe(attempt, "retryable", "TRANSPORT_INTERRUPTED");
        } else if (error != null && "UPSTREAM_STREAM_INTERRUPTED".equals(error.path("errorCode").asText())) {
            observe(attempt, "retryable", "STREAM_INTERRUPTED");
        } else {
            abandoned(attempt);
        }
    }

    public void abandoned(Attempt attempt) {
        if (attempt != Attempt.NONE && attempt.admission.startsWith("probe:")) observe(attempt, "neutral", "");
    }

    public Monitor monitor(String modelCode) {
        if (!enabled) return new Monitor(false, true, List.of());
        if (blank(modelCode) || modelCode.length() > 160) throw new IllegalArgumentException("模型编码无效");
        try {
            long now = System.currentTimeMillis();
            var routes = redis.opsForZSet().rangeByScore(modelKey(modelCode), now, Double.POSITIVE_INFINITY,
                    0, MONITOR_LIMIT);
            List<ChannelStatus> states = new ArrayList<>();
            if (routes != null) for (String route : routes) {
                var fields = redis.<String, String>opsForHash().multiGet(route, List.of("retryAt", "probeUntil", "reason"));
                if (fields == null || fields.get(0) == null) continue;
                long retryAt = Long.parseLong(fields.get(0));
                long probeUntil = fields.get(1) == null ? 0 : Long.parseLong(fields.get(1));
                String phase = probeUntil > now ? "PROBING" : retryAt > now ? "COOLDOWN" : "READY_TO_PROBE";
                states.add(new ChannelStatus(phase, fields.get(2), Math.max(retryAt, probeUntil)));
            }
            return new Monitor(true, true, List.copyOf(states));
        } catch (RuntimeException failure) {
            warnRedis(failure);
            return new Monitor(true, false, List.of());
        }
    }

    private void observe(Attempt attempt, String outcome, String reason) {
        if (attempt == Attempt.NONE) return;
        try {
            redis.execute(OBSERVE, List.of(attempt.route, attempt.modelKey), attempt.admission, outcome, reason,
                    UUID.randomUUID().toString(), failureWindowMs, cooldownMs, failureThreshold,
                    Long.toString(STATE_TTL_MS));
        } catch (RuntimeException failure) {
            warnRedis(failure);
        }
    }

    private static boolean accepted(ProviderSubmitResult result) {
        return blank(result.getConfirmedFailureCode()) && result.getSubmissionHttpStatus() == null
                && (!blank(result.getProviderTaskId())
                || !blank(result.getDirectUrl()) || !blank(result.getOssUrl()) || !blank(result.getDirectText())
                || result.getToolMessage() != null || result.getResultUrls() != null && !result.getResultUrls().isEmpty()
                || result.getImageOutputs() != null && !result.getImageOutputs().isEmpty()
                || result.getAudioOutputs() != null && !result.getAudioOutputs().isEmpty());
    }

    private static String modelKey(String modelCode) {
        return "media:route-health:{" + DigestUtils.sha256Hex(modelCode) + "}";
    }

    private static String fingerprint(AiModelConfigVo config) {
        return DigestUtils.sha256Hex(com.alibaba.fastjson2.JSON.toJSONString(List.of(config.getProviderId(),
                value(config.getProtocol()), value(config.getRealModelCode()), value(config.getBaseUrl()),
                value(config.getApiVersion()), value(config.getApiSuffix()), value(config.getApiKey()),
                value(config.getApiSecret()), value(config.getCredentialVersion()))));
    }

    private void warnRedis(RuntimeException failure) {
        long now = System.currentTimeMillis();
        long next = nextRedisWarning.get();
        if (now >= next && nextRedisWarning.compareAndSet(next, now + 60_000L)) {
            log.warn("媒体渠道运行健康存储不可用, errorType={}", failure.getClass().getSimpleName());
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String value(Object value) { return Objects.toString(value, ""); }
    private static String millis(int seconds) { return Long.toString(TimeUnit.SECONDS.toMillis(seconds)); }

    private static <T> DefaultRedisScript<T> script(String name, Class<T> type) {
        var script = new DefaultRedisScript<T>();
        script.setLocation(new ClassPathResource("redis/" + name));
        script.setResultType(type);
        return script;
    }

    public record Attempt(String route, String modelKey, String admission) {
        public static final Attempt NONE = new Attempt(null, null, null);
    }
    public record ChannelStatus(String phase, String reason, long nextProbeAt) {}
    public record Monitor(boolean enabled, boolean observable, List<ChannelStatus> channels) {}
}
