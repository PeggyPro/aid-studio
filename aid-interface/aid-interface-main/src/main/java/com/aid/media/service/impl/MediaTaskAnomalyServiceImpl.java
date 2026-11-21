package com.aid.media.service.impl;

import com.aid.aid.domain.media.*;
import com.aid.aid.mapper.*;
import com.aid.common.aid.json.JsonSafeId;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaTaskAnomalyConfigRequest;
import com.aid.media.dto.MediaTaskAnomalyRequests;
import com.aid.media.model.TaskRecheckResult;
import com.aid.media.service.MediaTaskAnomalyAdminService;
import com.aid.media.service.MediaTaskAnomalyRegistry;
import com.aid.media.service.TaskDispatchService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.pagehelper.PageInfo;
import com.github.pagehelper.PageHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class MediaTaskAnomalyServiceImpl implements MediaTaskAnomalyAdminService {
    private static final List<String> ACTIVE_TASK_STATUSES =
            List.of("PENDING", "WAIT_POLL", "WAIT_CALLBACK", "PROCESSING");
    private final AidMediaTaskAnomalyMapper anomalyMapper;
    private final AidMediaTaskAnomalyAuditMapper auditMapper;
    private final AidMediaTaskAnomalyConfigMapper configMapper;
    private final AidMediaTaskMapper taskMapper;
    private final TaskDispatchService dispatchService;
    private final MediaTaskAnomalyRegistry anomalyRegistry;

    @Override
    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>(anomalyMapper.selectAdminSummary());
        Map<String, Object> latest = anomalyMapper.selectLatestActive();
        result.put("latestAnomaly", latest == null ? null : latestSummary(latest));
        result.put("adminRefreshSeconds", currentConfig().getAdminRefreshSeconds());
        return result;
    }

    @Override
    public Map<String, Object> config() {
        AidMediaTaskAnomalyConfig c = currentConfig();
        return Map.of("enabled", c.getEnabled(),
                "anomalyThresholdSeconds", c.getAnomalyThresholdSeconds(),
                "autoRecheckIntervalSeconds", c.getAutoRecheckIntervalSeconds(),
                "adminRefreshSeconds", c.getAdminRefreshSeconds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(MediaTaskAnomalyConfigRequest r, Long uid, String operator) {
        AidMediaTaskAnomalyConfig c = configMapper.selectById(1L);
        boolean create = c == null;
        if (create) {
            c = new AidMediaTaskAnomalyConfig();
            c.setId(1L);
            c.setCreateBy(operator);
            c.setCreateTime(new Date());
        }
        c.setEnabled(r.getEnabled());
        c.setAnomalyThresholdSeconds(r.getAnomalyThresholdSeconds());
        c.setAutoRecheckIntervalSeconds(r.getAutoRecheckIntervalSeconds());
        c.setAdminRefreshSeconds(r.getAdminRefreshSeconds());
        c.setUpdateBy(operator);
        c.setUpdateTime(new Date());
        if (create) configMapper.insert(c); else configMapper.updateById(c);
        anomalyRegistry.invalidateConfig();
        audit(null, null, "UPDATE_CONFIG", uid, operator, "更新异常任务配置", null, null);
    }

    @Override
    public Map<String, Object> list(Long taskId, Long userId, String mediaType,
                                    String anomalyType, String anomalyStatus,
                                    Date start, Date end, int pageNum, int pageSize) {
        if (start != null && end != null && start.after(end)) {
            throw new ServiceException("时间范围错误");
        }
        String normalizedStatus = normalize(anomalyStatus);
        if (normalizedStatus != null && !List.of("OBSERVING", "PENDING", "WAITING",
                "COMPENSATION_PENDING", "CLOSED").contains(normalizedStatus)) {
            throw new ServiceException("异常状态无效");
        }
        PageHelper.startPage(Math.max(1, pageNum), Math.min(100, Math.max(1, pageSize)));
        List<Map<String, Object>> rows = anomalyMapper.selectAdminRows(taskId, userId,
                normalize(mediaType), normalize(anomalyType), normalizedStatus, start, end);
        long total = new PageInfo<>(rows).getTotal();
        List<Map<String, Object>> items = rows.stream().map(this::adminRowView).toList();
        return Map.of("items", items, "total", total);
    }

    @Override
    public Map<String, Object> detail(Long id) {
        require(id);
        Map<String, Object> row = anomalyMapper.selectAdminRowById(id);
        if (row == null) throw new ServiceException("异常关联任务不存在");
        Map<String, Object> result = adminRowView(row);
        result.put("audits", auditMapper.selectList(
                new LambdaQueryWrapper<AidMediaTaskAnomalyAudit>()
                        .eq(AidMediaTaskAnomalyAudit::getAnomalyId, id)
                        .orderByAsc(AidMediaTaskAnomalyAudit::getId).last("LIMIT 200"))
                .stream().map(this::auditView).toList());
        return result;
    }

    @Override
    public Map<String, Object> recheck(Long id, Long uid, String operator) {
        AidMediaTaskAnomaly a = requireActionable(id);
        TaskRecheckResult result = dispatchService.recheckTask(a.getTaskId(), false, null);
        recordAction(a, result, "RECHECK", null, uid, operator);
        return Map.of("result", recheckResultView(result), "anomaly", detail(id));
    }

    @Override
    public Map<String, Object> recheckTask(Long taskId, MediaTaskAnomalyRequests.TaskRecheck request,
                                          Long uid, String operator) {
        if (taskId == null || taskId <= 0) throw new ServiceException("任务 ID 无效");
        String reason = request == null ? null : limit(request.getReason());
        if (reason == null || reason.length() < 2) throw new ServiceException("请填写复查原因");
        AidMediaTask task = taskMapper.selectById(taskId);
        if (task == null) throw new ServiceException("任务不存在");
        boolean terminal = task.getStatus() != null && List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(task.getStatus());
        if (!terminal && (task.getStatus() == null || !ACTIVE_TASK_STATUSES.contains(task.getStatus())
                || task.getMediaType() == null || !List.of("IMAGE", "VIDEO", "AUDIO", "TEXT").contains(task.getMediaType())
                || (!"PENDING".equals(task.getStatus())
                    && (task.getProviderTaskId() == null || task.getProviderTaskId().isBlank())))) {
            throw new ServiceException("仅可复查已开始提交或正在生成的任务；排队任务请等待正常调度");
        }
        TaskRecheckResult result = dispatchService.recheckTask(taskId, false, null);
        AidMediaTaskAnomaly a = findByTaskId(taskId);
        if (!result.isTerminal() && !result.isQuerySuccessful()) {
            AidMediaTask currentTask = taskMapper.selectById(taskId);
            if (currentTask == null || currentTask.getStatus() == null || !ACTIVE_TASK_STATUSES.contains(currentTask.getStatus())) {
                result = dispatchService.recheckTask(taskId, false, null);
            } else {
                if (a == null) {
                    if (!Boolean.TRUE.equals(currentConfig().getEnabled())) {
                        throw new ServiceException("上游状态无法确认；请先启用异常治理后重新复查");
                    }
                    anomalyRegistry.recordQueryFailure(currentTask, result.getMessage(), null);
                    a = findByTaskId(taskId);
                    if (a == null) {
                        audit(null, taskId, "RECHECK_TASK", uid, operator, reason, task.getStatus(), result.getTaskStatus());
                        return Map.of("result", recheckResultView(result));
                    }
                }
                Date now = new Date();
                int changed = anomalyMapper.update(null, new LambdaUpdateWrapper<AidMediaTaskAnomaly>()
                        .eq(AidMediaTaskAnomaly::getId, a.getId())
                        .eq(AidMediaTaskAnomaly::getStatus, "OBSERVING")
                        .exists("SELECT 1 FROM aid_media_task WHERE id = {0} AND status IN ('WAIT_POLL','WAIT_CALLBACK','PROCESSING')", taskId)
                        .set(AidMediaTaskAnomaly::getStatus, "PENDING")
                        .set(AidMediaTaskAnomaly::getNextRecheckTime,
                                new Date(now.getTime() + Math.max(60, currentConfig().getAutoRecheckIntervalSeconds()) * 1000L))
                        .set(AidMediaTaskAnomaly::getUpdateBy, operator)
                        .set(AidMediaTaskAnomaly::getUpdateTime, now));
                if (changed > 0) {
                    audit(a.getId(), taskId, "MANUAL_REVIEW", uid, operator, reason, a.getStatus(), "PENDING");
                    a = anomalyMapper.selectById(a.getId());
                }
            }
        }
        if (a != null) {
            recordAction(a, result, "RECHECK_TASK", reason, uid, operator);
            return Map.of("result", recheckResultView(result), "anomaly", detail(a.getId()));
        }
        audit(null, taskId, "RECHECK_TASK", uid, operator, reason, task.getStatus(), result.getTaskStatus());
        return Map.of("result", recheckResultView(result));
    }

    private AidMediaTaskAnomaly findByTaskId(Long taskId) {
        return anomalyMapper.selectOne(new LambdaQueryWrapper<AidMediaTaskAnomaly>()
                .eq(AidMediaTaskAnomaly::getTaskId, taskId).last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void continueWait(Long id, MediaTaskAnomalyRequests.ContinueWait r,
                             Long uid, String operator) {
        AidMediaTaskAnomaly a = requireActionable(id);
        int changed = anomalyMapper.update(null, new LambdaUpdateWrapper<AidMediaTaskAnomaly>()
                .eq(AidMediaTaskAnomaly::getId, id)
                .in(AidMediaTaskAnomaly::getStatus, "PENDING", "WAITING")
                .set(AidMediaTaskAnomaly::getStatus, "WAITING")
                .set(AidMediaTaskAnomaly::getWaitReason, limit(r.getReason()))
                .set(AidMediaTaskAnomaly::getNextRecheckTime, r.getNextReviewTime())
                .set(AidMediaTaskAnomaly::getUpdateBy, operator)
                .set(AidMediaTaskAnomaly::getUpdateTime, new Date()));
        if (changed == 0) throw new ServiceException("异常状态已变化");
        taskMapper.update(null, new LambdaUpdateWrapper<AidMediaTask>()
                .eq(AidMediaTask::getId, a.getTaskId())
                .in(AidMediaTask::getStatus, ACTIVE_TASK_STATUSES)
                .set(AidMediaTask::getNextPollTime, r.getNextReviewTime()));
        audit(a.getId(), a.getTaskId(), "CONTINUE_WAIT", uid, operator,
                r.getReason(), a.getStatus(), "WAITING");
    }

    @Override
    public Map<String, Object> confirmFailure(Long id,
                                              MediaTaskAnomalyRequests.ConfirmFailure r,
                                              Long uid, String operator) {
        AidMediaTaskAnomaly a = requireActionable(id);
        TaskRecheckResult result = dispatchService.recheckTask(a.getTaskId(), true, r.getReason(), uid);
        recordAction(a, result, "CONFIRM_FAILURE", r.getReason(), uid, operator);
        return Map.of("result", recheckResultView(result), "anomaly", detail(id));
    }

    private void recordAction(AidMediaTaskAnomaly a, TaskRecheckResult r, String action,
                              String reason, Long uid, String operator) {
        if (!r.isTerminal()) {
            int seconds = Math.max(60, currentConfig().getAutoRecheckIntervalSeconds());
            Date now = new Date();
            anomalyMapper.update(null, new LambdaUpdateWrapper<AidMediaTaskAnomaly>()
                    .eq(AidMediaTaskAnomaly::getId, a.getId())
                    .ne(AidMediaTaskAnomaly::getStatus, "CLOSED")
                    .set(AidMediaTaskAnomaly::getLastRecheckTime, now)
                    .set(AidMediaTaskAnomaly::getNextRecheckTime,
                            new Date(now.getTime() + seconds * 1000L))
                    .set(AidMediaTaskAnomaly::getLastErrorMessage, limit(r.getMessage()))
                    .set(AidMediaTaskAnomaly::getUpdateBy, operator)
                    .set(AidMediaTaskAnomaly::getUpdateTime, now));
        }
        AidMediaTaskAnomaly current = anomalyMapper.selectById(a.getId());
        audit(a.getId(), a.getTaskId(), action, uid, operator, reason,
                a.getStatus(), current == null ? a.getStatus() : current.getStatus());
    }

    private AidMediaTaskAnomalyConfig currentConfig() {
        AidMediaTaskAnomalyConfig c = configMapper.selectById(1L);
        if (c != null) return c;
        c = new AidMediaTaskAnomalyConfig();
        c.setId(1L);
        c.setEnabled(true);
        c.setAnomalyThresholdSeconds(1800);
        c.setAutoRecheckIntervalSeconds(300);
        c.setAdminRefreshSeconds(60);
        return c;
    }

    private Map<String, Object> latestSummary(Map<String, Object> source) {
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("id", JsonSafeId.value(source.get("id")));
        latest.put("taskId", JsonSafeId.value(source.get("taskId")));
        latest.put("mediaType", source.get("mediaType"));
        latest.put("anomalyType", source.get("anomalyType"));
        latest.put("detectedAt", source.get("detectedAt"));
        return latest;
    }

    private Map<String, Object> adminRowView(Map<String, Object> source) {
        return JsonSafeId.fields(source, "id", "taskId", "userId", "projectId");
    }

    private Map<String, Object> auditView(AidMediaTaskAnomalyAudit audit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", JsonSafeId.value(audit.getId()));
        result.put("anomalyId", JsonSafeId.value(audit.getAnomalyId()));
        result.put("taskId", JsonSafeId.value(audit.getTaskId()));
        result.put("action", audit.getAction());
        result.put("operatorId", JsonSafeId.value(audit.getOperatorId()));
        result.put("operatorName", audit.getOperatorName());
        result.put("reason", audit.getReason());
        result.put("beforeStatus", audit.getBeforeStatus());
        result.put("afterStatus", audit.getAfterStatus());
        result.put("detailJson", audit.getDetailJson());
        result.put("createBy", audit.getCreateBy());
        result.put("createTime", audit.getCreateTime());
        return result;
    }

    private Map<String, Object> recheckResultView(TaskRecheckResult source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", JsonSafeId.value(source.getTaskId()));
        result.put("outcome", source.getOutcome());
        result.put("taskStatus", source.getTaskStatus());
        result.put("providerStatus", source.getProviderStatus());
        result.put("message", source.getMessage());
        result.put("terminal", source.isTerminal());
        result.put("querySuccessful", source.isQuerySuccessful());
        return result;
    }

    private AidMediaTaskAnomaly require(Long id) {
        AidMediaTaskAnomaly a = anomalyMapper.selectById(id);
        if (a == null) throw new ServiceException("异常记录不存在");
        return a;
    }

    private AidMediaTaskAnomaly requireActionable(Long id) {
        AidMediaTaskAnomaly a = require(id);
        if (!List.of("PENDING", "WAITING").contains(a.getStatus())) {
            throw new ServiceException("异常记录不可操作");
        }
        return a;
    }

    private void audit(Long anomalyId, Long taskId, String action, Long uid,
                       String operator, String reason, String before, String after) {
        AidMediaTaskAnomalyAudit x = new AidMediaTaskAnomalyAudit();
        x.setAnomalyId(anomalyId);
        x.setTaskId(taskId);
        x.setAction(action);
        x.setOperatorId(uid);
        x.setOperatorName(operator);
        x.setReason(limit(reason));
        x.setBeforeStatus(before);
        x.setAfterStatus(after);
        x.setCreateBy(operator);
        x.setCreateTime(new Date());
        auditMapper.insert(x);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private static String limit(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.length() > 500 ? result.substring(0, 500) : result;
    }
}
