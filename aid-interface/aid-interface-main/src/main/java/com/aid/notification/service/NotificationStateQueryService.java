package com.aid.notification.service;

import com.aid.common.constant.HttpStatus;
import com.aid.common.core.domain.ApiErrorDetails;
import com.aid.common.exception.ServiceException;
import com.aid.notification.domain.AidNotificationSequence;
import com.aid.notification.domain.AidNotificationUserState;
import com.aid.notification.mapper.AidSystemNotificationMapper;
import com.aid.notification.vo.NotificationViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 在同一一致性快照中读取用户通知状态。 */
@Service
@RequiredArgsConstructor
public class NotificationStateQueryService
{
    private final AidSystemNotificationMapper mapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationViews.State get(Long userId)
    {
        AidNotificationSequence sequence = mapper.selectSequence();
        if (sequence == null)
        {
            ApiErrorDetails detail = new ApiErrorDetails();
            detail.setErrorCode("NOTIFICATION_SEQUENCE_MISSING");
            detail.setReason("通知服务异常");
            detail.setRetryable(true);
            throw new ServiceException("通知服务异常", HttpStatus.ERROR).setClientErrorData(detail);
        }
        AidNotificationUserState userState = mapper.selectUserState(userId);
        long visibilityRevision = sequence.getVisibilityRevision() == null ? 0L : sequence.getVisibilityRevision();
        long readAllPublishSeq = userState == null || userState.getReadAllPublishSeq() == null
                ? 0L : userState.getReadAllPublishSeq();
        long readRevision = userState == null || userState.getReadRevision() == null
                ? 0L : userState.getReadRevision();
        NotificationViews.State state = new NotificationViews.State();
        state.setUnreadCount(mapper.countUnread(userId, readAllPublishSeq));
        state.setStateVersion(visibilityRevision + ":" + readRevision);
        return state;
    }
}
