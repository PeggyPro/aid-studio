package com.aid.runtime.service;

import com.aid.common.exception.ServiceException;
import com.aid.runtime.domain.AidRuntimeTestGrant;
import com.aid.runtime.dto.RuntimeTestGrantRequests;
import com.aid.runtime.mapper.AidRuntimeTestGrantMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Date;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A payer explicitly grants one billed draft trial to one administrator. */
@Service
@RequiredArgsConstructor
public class RuntimeTestGrantService {
    private final AidRuntimeTestGrantMapper grants;

    public AidRuntimeTestGrant peek(Long grantId) {
        AidRuntimeTestGrant grant = grants.selectById(grantId);
        if (grant == null) throw new ServiceException("试运行授权不存在", 404);
        return grant;
    }

    public AidRuntimeTestGrant create(RuntimeTestGrantRequests.Create request, Long userId) {
        AidRuntimeTestGrant grant = new AidRuntimeTestGrant();
        grant.setUserId(userId);
        grant.setAdminUserId(request.getAdminUserId());
        grant.setMemberType(request.getMemberType());
        grant.setMemberId(request.getMemberId());
        grant.setDraftDigest(request.getDraftDigest());
        grant.setExpiresAt(new Date(System.currentTimeMillis() + 10 * 60_000L));
        grant.setStatus("ISSUED");
        grant.setCreateTime(new Date());
        grant.setUpdateTime(new Date());
        grants.insert(grant);
        return grant;
    }

    public AidRuntimeTestGrant require(Long grantId, Long adminUserId, String type,
                                       Long memberId, String digest, String clientRequestId) {
        AidRuntimeTestGrant grant = grants.selectOne(Wrappers.<AidRuntimeTestGrant>lambdaQuery()
                .eq(AidRuntimeTestGrant::getId, grantId).last("FOR UPDATE"));
        if (grant == null || !Objects.equals(grant.getAdminUserId(), adminUserId)
                || !Objects.equals(grant.getMemberType(), type)
                || !Objects.equals(grant.getMemberId(), memberId)
                || !Objects.equals(grant.getDraftDigest(), digest))
            throw new ServiceException("草稿试运行授权不匹配", 403);
        if ("CONSUMED".equals(grant.getStatus())) {
            if (Objects.equals(grant.getClientRequestId(), clientRequestId)) return grant;
            throw new ServiceException("试运行授权已使用", 409);
        }
        if (!"ISSUED".equals(grant.getStatus()) || grant.getExpiresAt().before(new Date()))
            throw new ServiceException("试运行授权已过期", 409);
        return grant;
    }

    public void consume(AidRuntimeTestGrant grant, String clientRequestId, Long runId) {
        if ("CONSUMED".equals(grant.getStatus())) {
            if (!Objects.equals(grant.getRunId(), runId))
                throw new ServiceException("试运行授权已被其他请求使用", 409);
            return;
        }
        grant.setStatus("CONSUMED");
        grant.setClientRequestId(clientRequestId);
        grant.setRunId(runId);
        grant.setUpdateTime(new Date());
        grants.updateById(grant);
    }

    /** Claim a delegated trial before invoking the legacy orchestrator, so one grant cannot pay for two runs. */
    @Transactional(rollbackFor = Exception.class)
    public void reserveDelegated(Long grantId, Long adminUserId, Long agentId,
                                 String draftDigest, String clientRequestId) {
        AidRuntimeTestGrant grant = require(grantId, adminUserId, "AGENT", agentId,
                draftDigest, clientRequestId);
        if ("CONSUMED".equals(grant.getStatus())) return;
        grant.setStatus("CONSUMED");
        grant.setClientRequestId(clientRequestId);
        grant.setUpdateTime(new Date());
        grants.updateById(grant);
    }

    @Transactional(rollbackFor = Exception.class)
    public void bindDelegated(Long grantId, String clientRequestId, Long legacyRunId) {
        AidRuntimeTestGrant grant = grants.selectOne(Wrappers.<AidRuntimeTestGrant>lambdaQuery()
                .eq(AidRuntimeTestGrant::getId, grantId).last("FOR UPDATE"));
        if (grant == null || !"CONSUMED".equals(grant.getStatus())
                || !Objects.equals(grant.getClientRequestId(), clientRequestId))
            throw new ServiceException("委托试运行授权状态已改变", 409);
        if (grant.getRunId() != null && !Objects.equals(grant.getRunId(), legacyRunId))
            throw new ServiceException("委托试运行已关联另一运行", 409);
        grant.setRunId(legacyRunId);
        grant.setUpdateTime(new Date());
        grants.updateById(grant);
    }
}
