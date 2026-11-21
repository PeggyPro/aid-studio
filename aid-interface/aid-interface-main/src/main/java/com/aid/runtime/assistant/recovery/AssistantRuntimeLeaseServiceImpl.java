package com.aid.runtime.assistant.recovery;

import cn.hutool.core.util.StrUtil;
import com.aid.assistant.service.AssistantAutomaticTextExecution;
import com.aid.runtime.assistant.RuntimeLease;
import com.aid.runtime.domain.AidRuntimeRun;
import com.aid.runtime.domain.AidRuntimeRunStep;
import com.aid.runtime.mapper.AidRuntimeRunMapper;
import com.aid.runtime.mapper.AidRuntimeRunStepMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** MySQL lease claims with monotonically increasing fencing tokens. */
@Service
public class AssistantRuntimeLeaseServiceImpl implements AssistantRuntimeLeaseService
{
    private static final List<String> RECOVERABLE_RUN_STATES =
            Arrays.asList("READY", "RUNNING", "PAUSING", "RECONCILING");
    private static final List<String> CLAIMABLE_STEP_STATES =
            Arrays.asList("READY", "SUBMITTING", "INFLIGHT", "RECONCILING", "RETRY_WAIT");

    @Resource
    private AidRuntimeRunMapper runMapper;
    @Resource
    private AidRuntimeRunStepMapper stepMapper;

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> findDueRunIds(Date now, int limit)
    {
        requireTime(now);
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        return runMapper.selectList(new LambdaQueryWrapper<AidRuntimeRun>()
                        .select(AidRuntimeRun::getId)
                        .eq(AidRuntimeRun::getDelFlag, "0")
                        .isNotNull(AidRuntimeRun::getSessionId)
                        .and(query -> query.in(AidRuntimeRun::getStatus, RECOVERABLE_RUN_STATES)
                                .or(waiting -> waiting.eq(AidRuntimeRun::getStatus, "WAITING_APPROVAL")
                                        .eq(AidRuntimeRun::getStopRequested, false)
                                        .in(AidRuntimeRun::getNextAction, AssistantAutomaticTextExecution.ACTIONS)
                                        .apply("JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(request_json),request_json,'{}'), '$._automaticTextExecution')) = {0}",
                                                AssistantAutomaticTextExecution.VERSION)))
                        .and(query -> query.isNull(AidRuntimeRun::getNextRetryAt)
                                .or().le(AidRuntimeRun::getNextRetryAt, now))
                        .and(query -> query.isNull(AidRuntimeRun::getLeaseUntil)
                                .or().le(AidRuntimeRun::getLeaseUntil, now))
                        .orderByAsc(AidRuntimeRun::getNextRetryAt)
                        .orderByAsc(AidRuntimeRun::getId)
                        .last("LIMIT " + boundedLimit))
                .stream().map(AidRuntimeRun::getId).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Optional<RuntimeLease> tryClaimRun(Long runId, String owner, Date now, Date leaseUntil)
    {
        requireClaim(runId, owner, now, leaseUntil);
        Date persistedLeaseUntil = databaseTime(leaseUntil);
        requireFuture(now, persistedLeaseUntil);
        int updated = runMapper.update(null, new LambdaUpdateWrapper<AidRuntimeRun>()
                .set(AidRuntimeRun::getLeaseOwner, owner)
                .set(AidRuntimeRun::getLeaseUntil, persistedLeaseUntil)
                .set(AidRuntimeRun::getUpdateBy, owner)
                .set(AidRuntimeRun::getUpdateTime, now)
                .setSql("fencing_token=COALESCE(fencing_token,0)+1")
                .eq(AidRuntimeRun::getId, runId)
                .eq(AidRuntimeRun::getDelFlag, "0")
                .isNotNull(AidRuntimeRun::getSessionId)
                .and(query -> query.in(AidRuntimeRun::getStatus, RECOVERABLE_RUN_STATES)
                        .or(waiting -> waiting.eq(AidRuntimeRun::getStatus, "WAITING_APPROVAL")
                                .eq(AidRuntimeRun::getStopRequested, false)
                                .in(AidRuntimeRun::getNextAction, AssistantAutomaticTextExecution.ACTIONS)
                                .apply("JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(request_json),request_json,'{}'), '$._automaticTextExecution')) = {0}",
                                        AssistantAutomaticTextExecution.VERSION)))
                .and(query -> query.isNull(AidRuntimeRun::getNextRetryAt)
                        .or().le(AidRuntimeRun::getNextRetryAt, now))
                .and(query -> query.isNull(AidRuntimeRun::getLeaseUntil)
                        .or().le(AidRuntimeRun::getLeaseUntil, now)));
        if (updated != 1)
        {
            return Optional.empty();
        }
        AidRuntimeRun claimed = runMapper.selectById(runId);
        if (claimed == null || !owner.equals(claimed.getLeaseOwner())
                || claimed.getFencingToken() == null || !persistedLeaseUntil.equals(claimed.getLeaseUntil()))
        {
            throw new IllegalStateException("Runtime run lease claim was not persisted");
        }
        return Optional.of(lease(owner, claimed.getFencingToken(), claimed.getLeaseUntil()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean renewRun(Long runId, RuntimeLease lease, Date now, Date leaseUntil)
    {
        requireLease(runId, lease, now);
        requireFuture(now, leaseUntil);
        Date persistedLeaseUntil = databaseTime(leaseUntil);
        requireFuture(now, persistedLeaseUntil);
        return runMapper.update(null, runLeaseUpdate(runId, lease)
                .set(AidRuntimeRun::getLeaseUntil, persistedLeaseUntil)
                .set(AidRuntimeRun::getUpdateBy, lease.getOwner())
                .set(AidRuntimeRun::getUpdateTime, now)
                .gt(AidRuntimeRun::getLeaseUntil, now)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean releaseRun(Long runId, RuntimeLease lease, Date now)
    {
        requireLease(runId, lease, now);
        return runMapper.update(null, runLeaseUpdate(runId, lease)
                .set(AidRuntimeRun::getLeaseOwner, null)
                .set(AidRuntimeRun::getLeaseUntil, null)
                .set(AidRuntimeRun::getUpdateBy, lease.getOwner())
                .set(AidRuntimeRun::getUpdateTime, now)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean deferAndReleaseRun(Long runId, RuntimeLease lease, Date now, Date nextRetryAt)
    {
        requireLease(runId, lease, now);
        requireFuture(now, nextRetryAt);
        return runMapper.update(null, runLeaseUpdate(runId, lease)
                .set(AidRuntimeRun::getNextRetryAt, nextRetryAt)
                .set(AidRuntimeRun::getLeaseOwner, null)
                .set(AidRuntimeRun::getLeaseUntil, null)
                .set(AidRuntimeRun::getUpdateBy, lease.getOwner())
                .set(AidRuntimeRun::getUpdateTime, now)) == 1;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean isRunLeaseCurrent(Long runId, RuntimeLease lease, Date now)
    {
        requireLease(runId, lease, now);
        return runMapper.selectCount(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, runId)
                .eq(AidRuntimeRun::getDelFlag, "0")
                .isNotNull(AidRuntimeRun::getSessionId)
                .eq(AidRuntimeRun::getLeaseOwner, lease.getOwner())
                .eq(AidRuntimeRun::getFencingToken, lease.getFencingToken())
                .gt(AidRuntimeRun::getLeaseUntil, now)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Optional<RuntimeLease> tryClaimStep(Long runId, Long stepId, RuntimeLease runLease,
                                               Date now, Date leaseUntil)
    {
        requireLease(runId, runLease, now);
        requirePositive(stepId, "stepId");
        requireFuture(now, leaseUntil);
        AidRuntimeRun run = runMapper.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, runId)
                .eq(AidRuntimeRun::getDelFlag, "0")
                .last("FOR UPDATE"));
        if (!matches(run, runLease, now))
        {
            return Optional.empty();
        }
        Date boundedUntil = databaseTime(leaseUntil.after(run.getLeaseUntil())
                ? run.getLeaseUntil() : leaseUntil);
        requireFuture(now, boundedUntil);
        int updated = stepMapper.update(null, new LambdaUpdateWrapper<AidRuntimeRunStep>()
                .set(AidRuntimeRunStep::getLeaseOwner, runLease.getOwner())
                .set(AidRuntimeRunStep::getLeaseUntil, boundedUntil)
                .set(AidRuntimeRunStep::getUpdateBy, runLease.getOwner())
                .set(AidRuntimeRunStep::getUpdateTime, now)
                .setSql("fencing_token=COALESCE(fencing_token,0)+1")
                .eq(AidRuntimeRunStep::getId, stepId)
                .eq(AidRuntimeRunStep::getRunId, runId)
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .apply("EXISTS (SELECT 1 FROM aid_runtime_run assistant_run "
                        + "WHERE assistant_run.id=aid_runtime_run_step.run_id AND assistant_run.session_id IS NOT NULL)")
                .in(AidRuntimeRunStep::getStatus, CLAIMABLE_STEP_STATES)
                .and(query -> query.isNull(AidRuntimeRunStep::getLeaseUntil)
                        .or().le(AidRuntimeRunStep::getLeaseUntil, now)));
        if (updated != 1)
        {
            return Optional.empty();
        }
        AidRuntimeRunStep claimed = stepMapper.selectById(stepId);
        if (claimed == null || !Objects.equals(runId, claimed.getRunId())
                || !runLease.getOwner().equals(claimed.getLeaseOwner())
                || claimed.getFencingToken() == null || !boundedUntil.equals(claimed.getLeaseUntil()))
        {
            throw new IllegalStateException("Runtime step lease claim was not persisted");
        }
        return Optional.of(lease(runLease.getOwner(), claimed.getFencingToken(), claimed.getLeaseUntil()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean renewStep(Long runId, Long stepId, RuntimeLease runLease, RuntimeLease stepLease,
                             Date now, Date leaseUntil)
    {
        requireLease(runId, runLease, now);
        requireLease(runId, stepLease, now);
        requirePositive(stepId, "stepId");
        requireFuture(now, leaseUntil);
        AidRuntimeRun run = runMapper.selectOne(new LambdaQueryWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, runId)
                .eq(AidRuntimeRun::getDelFlag, "0")
                .last("FOR UPDATE"));
        if (!matches(run, runLease, now) || !Objects.equals(runLease.getOwner(), stepLease.getOwner()))
        {
            return false;
        }
        Date boundedUntil = databaseTime(leaseUntil.after(run.getLeaseUntil())
                ? run.getLeaseUntil() : leaseUntil);
        requireFuture(now, boundedUntil);
        return stepMapper.update(null, stepLeaseUpdate(runId, stepId, stepLease)
                .set(AidRuntimeRunStep::getLeaseUntil, boundedUntil)
                .set(AidRuntimeRunStep::getUpdateBy, stepLease.getOwner())
                .set(AidRuntimeRunStep::getUpdateTime, now)
                .gt(AidRuntimeRunStep::getLeaseUntil, now)) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean releaseStep(Long runId, Long stepId, RuntimeLease lease, Date now)
    {
        requireLease(runId, lease, now);
        requirePositive(stepId, "stepId");
        return stepMapper.update(null, stepLeaseUpdate(runId, stepId, lease)
                .set(AidRuntimeRunStep::getLeaseOwner, null)
                .set(AidRuntimeRunStep::getLeaseUntil, null)
                .set(AidRuntimeRunStep::getUpdateBy, lease.getOwner())
                .set(AidRuntimeRunStep::getUpdateTime, now)) == 1;
    }

    private LambdaUpdateWrapper<AidRuntimeRun> runLeaseUpdate(Long runId, RuntimeLease lease)
    {
        return new LambdaUpdateWrapper<AidRuntimeRun>()
                .eq(AidRuntimeRun::getId, runId)
                .eq(AidRuntimeRun::getDelFlag, "0")
                .isNotNull(AidRuntimeRun::getSessionId)
                .eq(AidRuntimeRun::getLeaseOwner, lease.getOwner())
                .eq(AidRuntimeRun::getFencingToken, lease.getFencingToken());
    }

    private LambdaUpdateWrapper<AidRuntimeRunStep> stepLeaseUpdate(Long runId, Long stepId, RuntimeLease lease)
    {
        return new LambdaUpdateWrapper<AidRuntimeRunStep>()
                .eq(AidRuntimeRunStep::getId, stepId)
                .eq(AidRuntimeRunStep::getRunId, runId)
                .eq(AidRuntimeRunStep::getDelFlag, "0")
                .apply("EXISTS (SELECT 1 FROM aid_runtime_run assistant_run "
                        + "WHERE assistant_run.id=aid_runtime_run_step.run_id AND assistant_run.session_id IS NOT NULL)")
                .eq(AidRuntimeRunStep::getLeaseOwner, lease.getOwner())
                .eq(AidRuntimeRunStep::getFencingToken, lease.getFencingToken());
    }

    private boolean matches(AidRuntimeRun run, RuntimeLease lease, Date now)
    {
        return run != null && run.getSessionId() != null && lease.getOwner().equals(run.getLeaseOwner())
                && Objects.equals(lease.getFencingToken(), run.getFencingToken())
                && run.getLeaseUntil() != null && run.getLeaseUntil().after(now);
    }

    private RuntimeLease lease(String owner, Long fencingToken, Date leaseUntil)
    {
        RuntimeLease lease = new RuntimeLease();
        lease.setOwner(owner);
        lease.setFencingToken(fencingToken);
        lease.setLeaseUntil(leaseUntil);
        return lease;
    }

    private Date databaseTime(Date value)
    {
        return new Date(value.getTime() / 1000L * 1000L);
    }

    private void requireClaim(Long id, String owner, Date now, Date leaseUntil)
    {
        requirePositive(id, "runId");
        if (StrUtil.isBlank(owner) || owner.length() > 64)
        {
            throw new IllegalArgumentException("owner is invalid");
        }
        requireTime(now);
        requireFuture(now, leaseUntil);
    }

    private void requireLease(Long runId, RuntimeLease lease, Date now)
    {
        requirePositive(runId, "runId");
        requireTime(now);
        if (lease == null || StrUtil.isBlank(lease.getOwner()) || lease.getOwner().length() > 64
                || lease.getFencingToken() == null || lease.getFencingToken() < 1
                || lease.getLeaseUntil() == null)
        {
            throw new IllegalArgumentException("lease is invalid");
        }
    }

    private void requireFuture(Date now, Date future)
    {
        requireTime(now);
        if (future == null || !future.after(now))
        {
            throw new IllegalArgumentException("lease or retry deadline must be in the future");
        }
    }

    private void requireTime(Date value)
    {
        if (value == null)
        {
            throw new IllegalArgumentException("time is required");
        }
    }

    private void requirePositive(Long value, String field)
    {
        if (value == null || value < 1)
        {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
