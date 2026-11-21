package com.aid.aid.service.impl;

import org.springframework.stereotype.Service;

import com.aid.aid.domain.AidDiscoveryMediaReviewLog;
import com.aid.aid.mapper.AidDiscoveryMediaReviewLogMapper;
import com.aid.aid.service.IAidDiscoveryMediaReviewLogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

/**
 * 发现内容审核流水基础服务实现。
 *
 * @author 视觉AID
 */
@Service
public class AidDiscoveryMediaReviewLogServiceImpl
        extends ServiceImpl<AidDiscoveryMediaReviewLogMapper, AidDiscoveryMediaReviewLog>
        implements IAidDiscoveryMediaReviewLogService
{
}
