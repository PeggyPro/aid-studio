package com.aid.aid.service.impl;

import org.springframework.stereotype.Service;

import com.aid.aid.domain.AidDiscoveryMedia;
import com.aid.aid.mapper.AidDiscoveryMediaMapper;
import com.aid.aid.service.IAidDiscoveryMediaService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

/**
 * 发现内容媒体基础服务实现。
 *
 * @author 视觉AID
 */
@Service
public class AidDiscoveryMediaServiceImpl extends ServiceImpl<AidDiscoveryMediaMapper, AidDiscoveryMedia>
        implements IAidDiscoveryMediaService
{
    @Override
    public int updateByCondition(AidDiscoveryMedia entity, Wrapper<AidDiscoveryMedia> wrapper)
    {
        return baseMapper.update(entity, wrapper);
    }
}
