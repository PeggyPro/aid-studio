package com.aid.aid.service;

import com.aid.aid.domain.AidDiscoveryMedia;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 发现内容媒体基础服务。
 *
 * @author 视觉AID
 */
public interface IAidDiscoveryMediaService extends IService<AidDiscoveryMedia>
{
    /** Executes an update and exposes the affected-row count for optimistic state transitions. */
    int updateByCondition(AidDiscoveryMedia entity, Wrapper<AidDiscoveryMedia> wrapper);

}
