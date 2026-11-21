package com.aid.notification.mapper;

import com.aid.notification.domain.AidNotificationOperation;
import com.aid.notification.domain.AidNotificationSequence;
import com.aid.notification.domain.AidNotificationUserState;
import com.aid.notification.domain.AidSystemNotification;
import com.aid.notification.domain.NotificationQueryRow;
import com.aid.notification.domain.NotificationRecipientRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 站内系统通知持久化接口。 */
public interface AidSystemNotificationMapper extends BaseMapper<AidSystemNotification>
{
    Date selectDatabaseNow();

    AidSystemNotification selectByIdForUpdate(@Param("id") Long id);

    List<Long> selectRecipientIds(@Param("notificationId") Long notificationId);

    int updateRecipientPublication(@Param("notificationId") Long notificationId,
                                   @Param("publishSeq") long publishSeq,
                                   @Param("publishTime") Date publishTime);

    int deleteRecipients(@Param("notificationId") Long notificationId);

    int insertRecipients(@Param("notificationId") Long notificationId,
                         @Param("userIds") List<Long> userIds,
                         @Param("createTime") Date createTime);

    long countValidRecipients(@Param("userIds") List<Long> userIds);

    long countCurrentCUsers();

    boolean isActiveCUser(@Param("userId") Long userId);

    List<NotificationRecipientRow> selectRecipientPage(@Param("keyword") String keyword,
                                                        @Param("offset") long offset,
                                                        @Param("limit") int limit);

    long countRecipientPage(@Param("keyword") String keyword);

    List<NotificationQueryRow> selectAdminPage(@Param("status") String status,
                                                @Param("keyword") String keyword,
                                                @Param("offset") long offset,
                                                @Param("limit") int limit);

    long countAdminPage(@Param("status") String status, @Param("keyword") String keyword);

    int updateDraft(@Param("item") AidSystemNotification item,
                    @Param("expectedVersion") long expectedVersion);

    int scheduleNotification(@Param("id") Long id,
                             @Param("expectedVersion") long expectedVersion,
                             @Param("scheduledTime") Date scheduledTime,
                             @Param("updaterId") Long updaterId,
                             @Param("updaterName") String updaterName,
                             @Param("updateTime") Date updateTime);

    int publishNotification(@Param("id") Long id,
                            @Param("expectedVersion") long expectedVersion,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("publishTime") Date publishTime,
                            @Param("publishSeq") long publishSeq,
                            @Param("recipientCount") long recipientCount,
                            @Param("updaterId") Long updaterId,
                            @Param("updaterName") String updaterName,
                            @Param("updateTime") Date updateTime);

    int withdrawNotification(@Param("id") Long id,
                             @Param("expectedVersion") long expectedVersion,
                             @Param("updaterId") Long updaterId,
                             @Param("updaterName") String updaterName,
                             @Param("updateTime") Date updateTime);

    int cancelSchedule(@Param("id") Long id,
                       @Param("expectedVersion") long expectedVersion,
                       @Param("updaterId") Long updaterId,
                       @Param("updaterName") String updaterName,
                       @Param("updateTime") Date updateTime);

    List<Long> selectDueScheduledIds(@Param("now") Date now, @Param("limit") int limit);

    AidNotificationSequence selectSequenceForUpdate();

    AidNotificationSequence selectSequence();

    int updateSequence(@Param("publishSeq") long publishSeq,
                       @Param("visibilityRevision") long visibilityRevision);

    int reserveOperation(@Param("operatorId") Long operatorId,
                         @Param("actionType") String actionType,
                         @Param("clientRequestId") String clientRequestId,
                         @Param("requestHash") String requestHash,
                         @Param("createTime") Date createTime);

    AidNotificationOperation selectOperationForUpdate(@Param("operatorId") Long operatorId,
                                                       @Param("actionType") String actionType,
                                                       @Param("clientRequestId") String clientRequestId);

    int completeOperation(@Param("operationId") Long operationId,
                          @Param("notificationId") Long notificationId,
                          @Param("responseVersion") long responseVersion,
                          @Param("completeTime") Date completeTime);

    List<NotificationQueryRow> selectUserPage(@Param("userId") Long userId,
                                               @Param("isRead") Boolean isRead,
                                               @Param("readAllPublishSeq") long readAllPublishSeq,
                                               @Param("offset") long offset,
                                               @Param("limit") int limit,
                                               @Param("candidateLimit") long candidateLimit);

    long countUserVisible(@Param("userId") Long userId,
                          @Param("isRead") Boolean isRead,
                          @Param("readAllPublishSeq") long readAllPublishSeq);

    NotificationQueryRow selectUserDetail(@Param("userId") Long userId,
                                           @Param("notificationId") Long notificationId);

    long countUnread(@Param("userId") Long userId,
                     @Param("readAllPublishSeq") long readAllPublishSeq);

    AidNotificationUserState selectUserState(@Param("userId") Long userId);

    AidNotificationUserState selectUserStateForUpdate(@Param("userId") Long userId);

    int ensureUserState(@Param("userId") Long userId);

    int updateReadAllState(@Param("userId") Long userId,
                           @Param("readAllPublishSeq") long readAllPublishSeq,
                           @Param("readAllTime") Date readAllTime,
                           @Param("updateTime") Date updateTime);

    int insertRead(@Param("userId") Long userId,
                   @Param("notificationId") Long notificationId,
                   @Param("readTime") Date readTime);

    int incrementReadRevision(@Param("userId") Long userId, @Param("updateTime") Date updateTime);

    AidSystemNotification selectRealtimeSummary(@Param("notificationId") Long notificationId);
}
