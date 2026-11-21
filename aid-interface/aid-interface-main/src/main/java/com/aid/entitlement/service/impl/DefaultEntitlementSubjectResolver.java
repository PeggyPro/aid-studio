package com.aid.entitlement.service.impl;

import com.aid.common.exception.ServiceException;
import com.aid.entitlement.service.EntitlementSubjectResolver;
import org.springframework.stereotype.Service;

@Service
public class DefaultEntitlementSubjectResolver implements EntitlementSubjectResolver {
    @Override
    public Subject resolve(Strategy strategy, Long actorUserId, Long projectOwnerUserId, Long teamId) {
        return switch (strategy) {
            case ACTOR -> userSubject(actorUserId, "操作用户权益主体不存在");
            case PROJECT_OWNER -> userSubject(projectOwnerUserId, "项目所有者权益主体不存在");
            case TEAM -> throw new ServiceException("团队权益尚未接入，不能使用团队额度或钱包");
        };
    }

    private Subject userSubject(Long id, String missingSubjectMessage) {
        if (id == null) {
            throw new ServiceException(missingSubjectMessage);
        }
        return new Subject("USER", id);
    }
}
