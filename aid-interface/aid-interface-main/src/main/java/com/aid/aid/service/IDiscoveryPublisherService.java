package com.aid.aid.service;

import java.util.Collection;
import java.util.List;

import com.aid.common.core.domain.entity.SysUser;

/** Provides the public user profile fields required by discovery content. */
public interface IDiscoveryPublisherService
{
    /** Batch-loads active publisher profiles without exposing other user fields. */
    List<SysUser> listPublicProfiles(Collection<Long> userIds);
}
