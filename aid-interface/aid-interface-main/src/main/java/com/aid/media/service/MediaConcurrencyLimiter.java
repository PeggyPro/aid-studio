package com.aid.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aid.aid.domain.AidAiModel;
import com.aid.aid.domain.AidAiProvider;
import com.aid.aid.domain.AidConfig;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.aid.service.IAidAiModelService;
import com.aid.aid.service.IAidAiProviderService;
import com.aid.aid.service.IAidConfigService;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.compose.config.MpsConfigManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import cn.hutool.core.util.StrUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 媒体任务并发限流服务：基于持久执行许可实现「全局 → 用户 → 模型 → 供应商」四维并发准入。
 * 平台全局上限最大（所有任务之和不得超过），其下依次为用户级、模型级（所有人对该模型的在途请求）、
 * 供应商级（该供应商下所有模型的在途请求之和）。
 *
 * 本类是供应商 / 模型两个维度并发上限的<strong>唯一权威执行方</strong>，计数对象为仍持有执行许可的请求
 * （即一次真实的上游厂商请求）。任务排队队列（com.aid.rps.queue）只管全局 / 用户两维的业务任务准入，
 * 不再重复表达供应商 / 模型并发，避免同一概念被两处配置。
 *
 * 配置来源：
 * - 全局 / 用户：aid_config（category=media）media_concurrent_limit_global / media_concurrent_limit_user；
 * - 模型：aid_ai_model.schedule_strategy_json.maxConcurrency（&lt;=0 或缺失 = 不限，仅受供应商上限约束）；
 * - 供应商：aid_ai_provider.schedule_strategy_json.maxConcurrency（&lt;=0 或缺失 = 不限，仅受全局上限约束）。
 * 四类配置均带 5 秒本地缓存，后台修改后最迟 5 秒生效；执行许可独立持久化，重启不从未终态任务重新制造计数。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaConcurrencyLimiter {

    // 兼容旧身份桶格式；执行计数持久化在独立许可表。
    private static final String KEY_USER_PREFIX = "media:concurrent:user:";

    /** 模型/供应商维度缺省占位桶（模型缺失、COMPOSE 等不在模型表的任务统一落此桶，按不限处理） */
    private static final String NONE_BUCKET = "none";

    /** 并发上限在 schedule_strategy_json 中的唯一键名（供应商行与模型行同名，各表示所在层级的上限） */
    private static final String FIELD_MAX_CONCURRENCY = "maxConcurrency";
    private static final String LEGACY_FIELD_MODEL_CONCURRENCY = "modelConcurrency";
    private static final String LEGACY_FIELD_PROVIDER_CONCURRENCY = "providerConcurrency";

    // aid_config 中的配置键名。
    private static final String CONFIG_CATEGORY = "media";
    private static final String CONFIG_GLOBAL_LIMIT = "media_concurrent_limit_global";
    private static final String CONFIG_USER_LIMIT = "media_concurrent_limit_user";
    private static final String CONFIG_USER_LIMIT_PREFIX = "media_concurrent_limit_user_";
    private static final String CONFIG_RECONCILIATION_WAIT_MINUTES = "media_reconciliation_queue_wait_minutes";
    private static final String CONFIG_RECONCILIATION_ALLOWANCE = "media_reconciliation_permit_allowance";

    // 本地配置缓存刷新间隔（秒）。
    private static final long CONFIG_CACHE_TTL_SECONDS = 5L;

    // 默认限流阈值（aid_config 无记录时兜底）。
    private static final int DEFAULT_GLOBAL_LIMIT = 50;
    private static final int DEFAULT_USER_LIMIT = 5;
    private static final int DEFAULT_RECONCILIATION_WAIT_MINUTES = 30;
    private static final int DEFAULT_RECONCILIATION_ALLOWANCE = 5;

    /** 表示"不限制"的并发值（模型/供应商维度缺省不限） */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // 持久执行许可统一承担四维准入与幂等释放。
    private final MediaExecutionPermitService executionPermits;
    private final AidMediaTaskMapper aidMediaTaskMapper;
    private final IAidConfigService aidConfigService;
    private final IAidAiModelService aidAiModelService;
    private final IAidAiProviderService aidAiProviderService;
    private final MpsConfigManager mpsConfigManager;

    // 本地配置缓存：上次刷新时间 + 缓存值。
    private volatile long configCacheTime = 0;
    private volatile int cachedGlobalLimit = DEFAULT_GLOBAL_LIMIT;
    private volatile int cachedUserLimit = DEFAULT_USER_LIMIT;
    private volatile int cachedReconciliationWaitMinutes = DEFAULT_RECONCILIATION_WAIT_MINUTES;
    private volatile int cachedReconciliationAllowance = DEFAULT_RECONCILIATION_ALLOWANCE;

    /** 用户专属上游并发覆盖：media_concurrent_limit_user_{userId}。 */
    private final Map<Long, long[]> userLimitOverrideCache = new ConcurrentHashMap<>();

    /** 模型元信息缓存：modelCode → [模型上限, providerId, 过期时刻]（5 秒本地缓存，减少 DB 查询） */
    private final Map<String, ModelMeta> modelMetaCache = new ConcurrentHashMap<>();

    /** 供应商上限缓存：providerId → [上限, 过期时刻] */
    private final Map<Long, long[]> providerLimitCache = new ConcurrentHashMap<>();

    /** 模型维度元信息：上限 + 所属供应商 ID + 缓存过期时刻。 */
    private static final class ModelMeta {
        final int modelLimit;
        final Long providerId;
        final long expireAt;

        ModelMeta(int modelLimit, Long providerId, long expireAt) {
            this.modelLimit = modelLimit;
            this.providerId = providerId;
            this.expireAt = expireAt;
        }
    }

    /** Reserve a durable identity before creating or dispatching a media task. */
    public String reserve(Long taskId, Long userId, String modelCode) {
        int globalLimit = refreshConfig();
        ModelMeta meta = resolveModelMeta(modelCode);
        return executionPermits.reserve(taskId, userId, resolveUserKey(userId),
                StrUtil.blankToDefault(modelCode, NONE_BUCKET), meta.providerId,
                new MediaExecutionPermitService.Limits(globalLimit, getUserLimitValue(userId),
                        meta.modelLimit, resolveProviderLimit(meta.providerId), cachedReconciliationAllowance));
    }

    /** A read-only reason; it never reserves or releases an admission. */
    public String getQueueWaitReason(AidMediaTask task) {
        return task == null ? null : getQueueWaitReason(java.util.List.of(task));
    }

    /** Only considered after admission refused a new request. Zero disables this deadline. */
    public boolean isReconciliationQueueWaitExpired(AidMediaTask task, long nowMillis) {
        refreshConfig();
        int waitMinutes = cachedReconciliationWaitMinutes;
        return waitMinutes > 0 && task.getCreateTime() != null
                && nowMillis - task.getCreateTime().getTime() >= waitMinutes * 60_000L
                && MediaQueueWaitPolicy.UPSTREAM_RECONCILIATION.equals(getQueueWaitReason(task));
    }

    /** Parent estimates batch children by owner/model, avoiding one query per child. */
    public String getQueueWaitReason(java.util.List<AidMediaTask> group) {
        if (group == null || group.isEmpty()) return null;
        var candidates = group.stream().filter(t -> t != null && t.getId() != null
                && ("QUEUED".equals(t.getStatus()) || "PENDING".equals(t.getStatus()))).toList();
        if (candidates.isEmpty()) return null;
        AidMediaTask first = candidates.get(0);
        if (candidates.stream().anyMatch(t -> !java.util.Objects.equals(t.getUserId(), first.getUserId())
                || !java.util.Objects.equals(t.getModelName(), first.getModelName())))
            throw new IllegalArgumentException("MEDIA_WAIT_GROUP_IDENTITY_INVALID");
        ModelMeta meta = resolveModelMeta(first.getModelName());
        return executionPermits.queueWaitReason(candidates.stream().map(AidMediaTask::getId).toList(),
                resolveUserKey(first.getUserId()), StrUtil.blankToDefault(first.getModelName(), NONE_BUCKET),
                meta.providerId, new MediaExecutionPermitService.Limits(refreshConfig(),
                        getUserLimitValue(first.getUserId()), meta.modelLimit, resolveProviderLimit(meta.providerId),
                        cachedReconciliationAllowance));
    }

    public String getCapacityWaitReason(AidMediaTask task) {
        if (task == null || task.getId() == null || !"QUEUED".equals(task.getStatus())) return null;
        ModelMeta meta = resolveModelMeta(task.getModelName());
        return executionPermits.capacityWaitReason(task.getId(), resolveUserKey(task.getUserId()),
                StrUtil.blankToDefault(task.getModelName(), NONE_BUCKET), meta.providerId,
                new MediaExecutionPermitService.Limits(refreshConfig(), getUserLimitValue(task.getUserId()),
                        meta.modelLimit, resolveProviderLimit(meta.providerId), cachedReconciliationAllowance));
    }

    public void activate(AidMediaTask task, Runnable closeTransport) {
        activate(task, "TEXT".equals(task.getMediaType()), closeTransport);
    }

    public void activate(AidMediaTask task, boolean synchronous, Runnable closeTransport) {
        executionPermits.activate(task.getExecutionPermitToken(),
                synchronous ? synchronousMode(task) : MediaExecutionPermitService.Mode.SUBMITTING, closeTransport);
    }

    private static MediaExecutionPermitService.Mode synchronousMode(AidMediaTask task) {
        return switch(task.getMediaType()) {
            case "TEXT" -> MediaExecutionPermitService.Mode.SYNC_TEXT;
            case "IMAGE" -> MediaExecutionPermitService.Mode.SYNC_IMAGE;
            case "AUDIO" -> MediaExecutionPermitService.Mode.SYNC_AUDIO;
            default -> throw new IllegalArgumentException("Unsupported synchronous media contract");
        };
    }

    public void requireOwned(AidMediaTask task) {
        executionPermits.requireOwned(task.getExecutionPermitToken());
    }

    public void release(AidMediaTask task) {
        if (task != null && task.getExecutionPermitToken() != null)
            executionPermits.release(task.getExecutionPermitToken(),
                    MediaExecutionPermitService.ReleaseReason.TASK_TERMINAL);
    }

    /** Capture this token before scheduling a terminal callback; never re-read a newer task token. */
    public void releaseToken(String token) {
        if (token != null) executionPermits.release(token,
                MediaExecutionPermitService.ReleaseReason.TASK_TERMINAL);
    }

    public void abortReservation(String token) {
        if (token != null) executionPermits.release(token,
                MediaExecutionPermitService.ReleaseReason.RESERVATION_ABORTED);
    }

    public void closeTextExecution(AidMediaTask task) {
        if (task != null && "TEXT".equals(task.getMediaType()) && task.getExecutionPermitToken() != null)
            executionPermits.release(task.getExecutionPermitToken(),
                    MediaExecutionPermitService.ReleaseReason.LOCAL_TEXT_CONNECTION_CLOSED);
    }

    public void closeSynchronousExecution(AidMediaTask task) {
        if (task != null && task.getExecutionPermitToken() != null)
            executionPermits.finishLocalCall(task.getExecutionPermitToken());
    }

    public boolean releaseReturnedSynchronous(AidMediaTask task) {
        return executionPermits.releaseReturnedSynchronous(task.getExecutionPermitToken(), task.getId(),
                synchronousMode(task));
    }

    /**
     * 查询指定排队任务在全局队列中的位置。
     *
     * @param userId 用户 ID（保留参数，当前按全局排序）
     * @param taskId 当前任务 ID
     * @return 排队位置（从 1 开始），不在排队中返回 null
     */
    public Integer getQueuePosition(Long userId, Long taskId) {
        if (taskId == null) {
            return null;
        }
        AidMediaTask current = aidMediaTaskMapper.selectById(taskId);
        if (current == null || !MediaTaskStatus.QUEUED.name().equals(current.getStatus())) {
            return null;
        }
        return queuePosition(current, false);
    }

    public Integer getModelQueuePosition(AidMediaTask current) {
        if (current == null || !MediaTaskStatus.QUEUED.name().equals(current.getStatus())
                || current.getCreateTime() == null) return null;
        return queuePosition(current, true);
    }

    private Integer queuePosition(AidMediaTask current, boolean sameModel) {
        LambdaQueryWrapper<AidMediaTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AidMediaTask::getStatus, MediaTaskStatus.QUEUED.name());
        if (sameModel) wrapper.eq(AidMediaTask::getModelName, current.getModelName());
        wrapper.and(w -> w
            .lt(AidMediaTask::getCreateTime, current.getCreateTime())
            .or()
            .nested(n -> n
                .eq(AidMediaTask::getCreateTime, current.getCreateTime())
                .lt(AidMediaTask::getId, current.getId())
            )
        );
        Long ahead = aidMediaTaskMapper.selectCount(wrapper);
        return ahead.intValue() + 1;
    }

    public int getGlobalCount() {
        return (int) Math.min(Integer.MAX_VALUE, executionPermits.count(null, null, null));
    }

    public int getUserCount(Long userId) {
        return userId == null ? 0 : (int) Math.min(Integer.MAX_VALUE,
                executionPermits.count(resolveUserKey(userId), null, null));
    }

    public int getModelCount(String modelCode) {
        return StrUtil.isBlank(modelCode) ? 0 : (int) Math.min(Integer.MAX_VALUE,
                executionPermits.count(null, modelCode, null));
    }

    public int getProviderCount(Long providerId) {
        return providerId == null ? 0 : (int) Math.min(Integer.MAX_VALUE,
                executionPermits.count(null, null, providerId));
    }

    public Map<String, Integer> getModelCountBatch(List<String> modelCodes) {
        return executionPermits.countModels(modelCodes);
    }

    public Map<Long, Integer> getProviderCountBatch(List<Long> providerIds) {
        return executionPermits.countProviders(providerIds);
    }

    public int getGlobalLimit() {
        refreshConfig();
        return cachedGlobalLimit;
    }

    /**
     * 获取单用户并发上限。
     */
    public int getUserLimitValue() {
        refreshConfig();
        return cachedUserLimit;
    }

    /**
     * 获取指定用户的上游在途任务并发上限；专属配置优先于系统默认值。
     */
    public int getUserLimitValue(Long userId) {
        refreshConfig();
        if (Objects.isNull(userId)) {
            return cachedUserLimit;
        }
        long now = System.currentTimeMillis();
        long[] cached = userLimitOverrideCache.get(userId);
        if (cached != null && cached[1] > now) {
            return cached[0] > 0 ? (int) cached[0] : cachedUserLimit;
        }
        int override = readConfigValue(CONFIG_USER_LIMIT_PREFIX + userId, -1);
        userLimitOverrideCache.put(userId,
                new long[]{override, now + CONFIG_CACHE_TTL_SECONDS * 1000L});
        return override > 0 ? override : cachedUserLimit;
    }

    /**
     * 返回指定任务实际受约束的最小并发上限，供 ETA 按并行波次估算批量完成时间。
     * 无限维度不参与取最小值；返回值至少为 1。
     */
    public int getEffectiveConcurrencyLimit(Long userId, String modelCode) {
        refreshConfig();
        ModelMeta meta = resolveModelMeta(modelCode);
        int effective = Math.min(cachedGlobalLimit, getUserLimitValue(userId));
        if (meta.modelLimit != UNLIMITED) {
            effective = Math.min(effective, meta.modelLimit);
        }
        int providerLimit = resolveProviderLimit(meta.providerId);
        if (providerLimit != UNLIMITED) {
            effective = Math.min(effective, providerLimit);
        }
        return Math.max(1, effective);
    }

    /**
     * 刷新本地配置缓存（5 秒间隔从 aid_config 表读取）。
     */
    private int refreshConfig() {
        long now = System.currentTimeMillis();
        if (now - configCacheTime < CONFIG_CACHE_TTL_SECONDS * 1000L) {
            return cachedGlobalLimit;
        }
        try {
            cachedGlobalLimit = readConfigValue(CONFIG_GLOBAL_LIMIT, DEFAULT_GLOBAL_LIMIT);
            cachedUserLimit = readConfigValue(CONFIG_USER_LIMIT, DEFAULT_USER_LIMIT);
            int waitMinutes = readConfigValue(CONFIG_RECONCILIATION_WAIT_MINUTES, DEFAULT_RECONCILIATION_WAIT_MINUTES);
            cachedReconciliationWaitMinutes = waitMinutes >= 0 && waitMinutes <= 1440
                    ? waitMinutes : DEFAULT_RECONCILIATION_WAIT_MINUTES;
            int allowance = readConfigValue(CONFIG_RECONCILIATION_ALLOWANCE, DEFAULT_RECONCILIATION_ALLOWANCE);
            cachedReconciliationAllowance = allowance >= 0 && allowance <= 20
                    ? allowance : DEFAULT_RECONCILIATION_ALLOWANCE;
            configCacheTime = now;
        } catch (Exception e) {
            log.warn("媒体并发限流: 读取配置失败，使用缓存值", e);
        }
        return cachedGlobalLimit;
    }

    /**
     * 从 aid_config 表读取配置值，解析失败或未配置时返回默认值。
     */
    private int readConfigValue(String configName, int defaultValue) {
        try {
            LambdaQueryWrapper<AidConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AidConfig::getCategory, CONFIG_CATEGORY);
            wrapper.eq(AidConfig::getConfigName, configName);
            wrapper.select(AidConfig::getConfigValue);
            wrapper.last("limit 1");
            AidConfig config = aidConfigService.getOne(wrapper);
            if (config != null && config.getConfigValue() != null) {
                return Integer.parseInt(config.getConfigValue().trim());
            }
        } catch (Exception ignored) {
            // 配置不存在或解析失败时返回默认值。
        }
        return defaultValue;
    }

    /**
     * 解析模型维度元信息（上限 + 所属供应商 ID），带 5 秒本地缓存。
     * 模型不在 aid_ai_model（如 COMPOSE 合成任务）或编码为空 → 不限 + 无供应商。
     */
    private ModelMeta resolveModelMeta(String modelCode) {
        if (StrUtil.isBlank(modelCode)) {
            return new ModelMeta(UNLIMITED, null, 0L);
        }
        if (Set.of(MpsConfigManager.MODE_TENCENT_MPS, MpsConfigManager.MODE_ALIYUN_IMS,
                MpsConfigManager.MODE_LOCAL_FFMPEG).contains(modelCode)) {
            return new ModelMeta(mpsConfigManager.getMaxConcurrency(modelCode), null,
                    System.currentTimeMillis() + CONFIG_CACHE_TTL_SECONDS * 1000L);
        }
        long now = System.currentTimeMillis();
        ModelMeta cached = modelMetaCache.get(modelCode);
        if (cached != null && cached.expireAt > now) {
            return cached;
        }
        int limit = UNLIMITED;
        Long providerId = null;
        try {
            // 特别标注：只查模型上限解析必要字段（model_code + provider_id + schedule_strategy_json）。
            AidAiModel model = aidAiModelService.getOne(
                    Wrappers.<AidAiModel>lambdaQuery()
                            .select(AidAiModel::getModelCode, AidAiModel::getProviderId, AidAiModel::getScheduleStrategyJson)
                            .eq(AidAiModel::getModelCode, modelCode)
                            .last("LIMIT 1"), false);
            if (model != null) {
                providerId = model.getProviderId();
                limit = parseModelConcurrency(model.getScheduleStrategyJson());
            }
        } catch (Exception e) {
            log.warn("媒体并发限流: 读取模型上限失败, modelCode={}, 默认不限: {}", modelCode, e.getMessage());
        }
        ModelMeta meta = new ModelMeta(limit, providerId, now + CONFIG_CACHE_TTL_SECONDS * 1000L);
        modelMetaCache.put(modelCode, meta);
        return meta;
    }

    /**
     * 解析供应商维度上限（aid_ai_provider.schedule_strategy_json.maxConcurrency），带 5 秒本地缓存。
     */
    private int resolveProviderLimit(Long providerId) {
        if (Objects.isNull(providerId)) {
            return UNLIMITED;
        }
        long now = System.currentTimeMillis();
        long[] cached = providerLimitCache.get(providerId);
        if (cached != null && cached[1] > now) {
            return (int) cached[0];
        }
        int limit = UNLIMITED;
        try {
            // 特别标注：只查供应商上限解析必要字段（id + schedule_strategy_json）。
            AidAiProvider provider = aidAiProviderService.getOne(
                    Wrappers.<AidAiProvider>lambdaQuery()
                            .select(AidAiProvider::getId, AidAiProvider::getScheduleStrategyJson)
                            .eq(AidAiProvider::getId, providerId)
                            .last("LIMIT 1"), false);
            if (provider != null) {
                limit = parseProviderConcurrency(provider.getScheduleStrategyJson());
            }
        } catch (Exception e) {
            log.warn("媒体并发限流: 读取供应商上限失败, providerId={}, 默认不限: {}", providerId, e.getMessage());
        }
        providerLimitCache.put(providerId, new long[]{limit, now + CONFIG_CACHE_TTL_SECONDS * 1000L});
        return limit;
    }

    /**
     * 从 schedule_strategy_json 解析并发上限 maxConcurrency（缺省/非法/&lt;=0 视为不限制）。
     * 供应商行与模型行共用本方法，是并发上限的唯一解析入口，后台校验与监控页一并复用。
     *
     * @param scheduleStrategyJson 供应商 / 模型的调度策略 JSON
     * @return 上限；缺省/非法/&lt;=0 返回 {@link #UNLIMITED}
     */
    public static int parseMaxConcurrency(String scheduleStrategyJson) {
        return parseConcurrency(scheduleStrategyJson, null);
    }

    /** 解析模型并发，兼容升级前的 modelConcurrency 字段。 */
    public static int parseModelConcurrency(String scheduleStrategyJson) {
        return parseConcurrency(scheduleStrategyJson, LEGACY_FIELD_MODEL_CONCURRENCY);
    }

    /** 解析供应商并发，兼容升级前的 providerConcurrency 字段。 */
    public static int parseProviderConcurrency(String scheduleStrategyJson) {
        return parseConcurrency(scheduleStrategyJson, LEGACY_FIELD_PROVIDER_CONCURRENCY);
    }

    private static int parseConcurrency(String scheduleStrategyJson, String legacyFieldName) {
        if (StrUtil.isBlank(scheduleStrategyJson)) {
            return UNLIMITED;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(scheduleStrategyJson);
            JsonNode field = node.get(FIELD_MAX_CONCURRENCY);
            if ((field == null || !field.isNumber()) && StrUtil.isNotBlank(legacyFieldName)) {
                field = node.get(legacyFieldName);
                if (field != null && field.isNumber()) {
                    log.warn("媒体并发限流: 检测到旧字段 {}，请迁移为 {}", legacyFieldName, FIELD_MAX_CONCURRENCY);
                }
            }
            if (field == null || !field.isNumber()) {
                return UNLIMITED;
            }
            int v = field.asInt();
            return v > 0 ? v : UNLIMITED;
        } catch (Exception e) {
            log.warn("媒体并发限流: 解析 schedule_strategy_json.{} 失败, 默认不限: {}",
                FIELD_MAX_CONCURRENCY, e.getMessage());
            return UNLIMITED;
        }
    }

    /**
     * 根据用户 ID 拼接 Redis Key。
     * 匿名用户按线程上下文 clientIp 分桶，避免所有匿名请求共享同一上限，
     * 防止攻击者用大量匿名并发打满单一匿名桶导致合法匿名请求失败。
     */
    private String resolveUserKey(Long userId) {
        if (userId != null) {
            return KEY_USER_PREFIX + userId;
        }
        String ip = null;
        try {
            ip = com.aid.common.utils.ip.IpUtils.getIpAddr();
        } catch (Throwable ignore) {
            // ServletRequest 不存在时安静降级
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            return KEY_USER_PREFIX + "anonymous";
        }
        // 将 IP 中 '.' ':' 替换成 '_' 避免与其它 Redis 命名空间分隔符冲突
        String safeIp = ip.replace('.', '_').replace(':', '_');
        return KEY_USER_PREFIX + "anon:" + safeIp;
    }

}
