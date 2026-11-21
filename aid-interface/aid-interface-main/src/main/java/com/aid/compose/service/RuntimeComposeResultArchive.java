package com.aid.compose.service;

import cn.hutool.core.util.StrUtil;
import com.aid.aid.domain.media.AidMediaResult;
import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaResultMapper;
import com.aid.billing.dto.BillingBudgetContext;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Objects;

/** Copies an already verified compose storage receipt to its single result; never uploads or bills. */
@Service
@RequiredArgsConstructor
public class RuntimeComposeResultArchive {
    private final AidMediaResultMapper results;

    public void ensureManifest(AidMediaTask task) {
        if (task == null || !"COMPOSE".equals(task.getMediaType())
                || !BillingBudgetContext.isBudgetManagedBizTaskType(task.getBizTaskType())
                || !"SUCCEEDED".equals(task.getStatus()) || StrUtil.isBlank(task.getOssUrl())) return;
        String stored = task.getOssUrl();
        if (task.getId() == null || task.getUserId() == null || !stored.startsWith("/")
                || stored.startsWith("//") || stored.contains("://")
                || stored.contains("..") || stored.contains("\\") || StrUtil.isBlank(task.getOriginUrl())) {
            throw new ServiceException("合成结果存储凭证无效");
        }
        var manifest = results.selectList(new LambdaQueryWrapper<AidMediaResult>()
                .select(AidMediaResult::getId, AidMediaResult::getTaskId, AidMediaResult::getResultIndex,
                        AidMediaResult::getMediaType, AidMediaResult::getOriginUrl, AidMediaResult::getOssUrl)
                .eq(AidMediaResult::getTaskId, task.getId()).last("LIMIT 2"));
        if (manifest == null || manifest.size() != 1) throw new ServiceException("合成结果清单不完整");
        AidMediaResult result = manifest.get(0);
        if (result == null || result.getId() == null || !Objects.equals(result.getTaskId(), task.getId())
                || !Objects.equals(result.getResultIndex(), 0)
                || !"VIDEO".equals(result.getMediaType())
                || !Objects.equals(result.getOriginUrl(), task.getOriginUrl())
                || StrUtil.isNotBlank(result.getOssUrl()) && !stored.equals(result.getOssUrl())) {
            throw new ServiceException("合成结果清单身份不一致");
        }
        if (stored.equals(result.getOssUrl())) return;
        var update = new LambdaUpdateWrapper<AidMediaResult>()
                .eq(AidMediaResult::getId, result.getId()).eq(AidMediaResult::getTaskId, task.getId())
                .eq(AidMediaResult::getMediaType, "VIDEO").eq(AidMediaResult::getOriginUrl, task.getOriginUrl())
                .and(q -> q.isNull(AidMediaResult::getOssUrl).or().eq(AidMediaResult::getOssUrl, ""));
        update.set(AidMediaResult::getOssUrl, stored).set(AidMediaResult::getUpdateTime, new Date());
        // A concurrent equal repair is harmless. The next fenced archive read verifies the receipt.
        results.update(null, update);
    }
}
