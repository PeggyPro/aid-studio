package com.aid.aid.service.impl;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;

import com.aid.aid.mapper.DiscoveryPublisherMapper;
import com.aid.aid.service.IDiscoveryPublisherService;
import com.aid.common.core.domain.entity.SysUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/** MyBatis-Plus publisher profile query service. */
@Service
public class DiscoveryPublisherServiceImpl implements IDiscoveryPublisherService
{
    private final DiscoveryPublisherMapper publisherMapper;

    public DiscoveryPublisherServiceImpl(DiscoveryPublisherMapper publisherMapper)
    {
        this.publisherMapper = publisherMapper;
    }

    @Override
    public List<SysUser> listPublicProfiles(Collection<Long> userIds)
    {
        if (userIds == null || userIds.isEmpty())
        {
            return Collections.emptyList();
        }
        return publisherMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getUserId, SysUser::getNickName, SysUser::getAvatar)
                .in(SysUser::getUserId, userIds)
                .eq(SysUser::getDelFlag, "0"));
    }
}
