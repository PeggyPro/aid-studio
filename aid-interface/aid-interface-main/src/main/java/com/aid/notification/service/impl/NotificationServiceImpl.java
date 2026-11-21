package com.aid.notification.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.common.constant.HttpStatus;
import com.aid.common.core.domain.ApiErrorDetails;
import com.aid.common.exception.ServiceException;
import com.aid.notification.domain.AidNotificationOperation;
import com.aid.notification.domain.AidNotificationSequence;
import com.aid.notification.domain.AidNotificationUserState;
import com.aid.notification.domain.AidSystemNotification;
import com.aid.notification.domain.NotificationQueryRow;
import com.aid.notification.domain.NotificationRecipientRow;
import com.aid.notification.dto.NotificationAdminRequests;
import com.aid.notification.dto.NotificationUserRequests;
import com.aid.notification.mapper.AidSystemNotificationMapper;
import com.aid.notification.service.NotificationRealtimeService;
import com.aid.notification.service.NotificationService;
import com.aid.notification.service.NotificationStateQueryService;
import com.aid.notification.vo.NotificationViews;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 站内系统通知的事务状态机与用户阅读实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService
{
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_SCHEDULED = "SCHEDULED";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_WITHDRAWN = "WITHDRAWN";
    private static final String RECIPIENT_ALL = "ALL";
    private static final String RECIPIENT_USERS = "USERS";
    private static final String FORMAT_MARKDOWN = "MARKDOWN";
    private static final String FORMAT_PLAIN_TEXT = "PLAIN_TEXT";
    private static final String MESSAGE_TYPE_SYSTEM = "SYSTEM";
    private static final int SUMMARY_GENERATED_LENGTH = 120;
    private static final Pattern MARKDOWN_MARKERS = Pattern.compile("[`#>*_~\\[\\]()]");

    private final AidSystemNotificationMapper mapper;
    private final NotificationStateQueryService stateQueryService;
    private final NotificationRealtimeService realtimeService;
    private final PlatformTransactionManager transactionManager;

    @Value("${notification.page-size.max:100}")
    private int configuredMaxPageSize;

    @Value("${notification.selected-recipients.max:5000}")
    private int configuredMaxSelectedRecipients;

    @Value("${notification.scheduled.batch-size:50}")
    private int scheduledBatchSize;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationViews.Page<NotificationViews.Notification> adminList(
            NotificationAdminRequests.ListRequest request)
    {
        int pageNum = pageNum(request.getPageNum());
        int pageSize = pageSize(request.getPageSize());
        String keyword = trimToNull(request.getKeyword());
        String status = trimToNull(request.getStatus());
        long total = mapper.countAdminPage(status, keyword);
        long pageOffset = offset(pageNum, pageSize);
        List<NotificationQueryRow> rows = pageOffset >= total ? List.of()
                : mapper.selectAdminPage(status, keyword, pageOffset, pageSize);
        Long currentAudience = rows.stream().anyMatch(row -> RECIPIENT_ALL.equals(row.getRecipientType()))
                ? mapper.countCurrentCUsers() : null;
        List<NotificationViews.Notification> items = rows.stream()
                .map(row -> toView(row, currentAudience)).toList();
        return page(items, total, pageNum, pageSize, null);
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationViews.Notification adminDetail(String id)
    {
        AidSystemNotification item = mapper.selectById(parseId(id, "id"));
        if (item == null)
        {
            throw failure(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "通知不存在", "id");
        }
        return toAdminView(item);
    }

    @Override
    @Transactional
    public NotificationViews.Notification save(Long operatorId, String operatorName,
                                                NotificationAdminRequests.Save request)
    {
        normalizeSaveRequest(request);
        String requestHash = hashRequest(request);
        AidNotificationOperation operation = reserveOperation(operatorId, "SAVE",
                request.getClientRequestId(), requestHash);
        if (operation.getCompleteTime() != null)
        {
            return replayCompletedOperation(operation);
        }

        Date now = databaseNow();
        List<Long> recipientIds = normalizeRecipients(request.getRecipientType(), request.getRecipientUserIds());
        long recipientCount = recipientIds.size();
        AidSystemNotification item;
        if (StrUtil.isBlank(request.getId()))
        {
            if (StrUtil.isNotBlank(request.getExpectedVersion()))
            {
                throw failure(HttpStatus.BAD_REQUEST, "NOTIFICATION_VERSION_UNEXPECTED", "版本号多余", "expectedVersion");
            }
            item = new AidSystemNotification();
            item.setTitle(request.getTitle());
            item.setSummary(request.getSummary());
            item.setContent(request.getContent());
            item.setContentFormat(request.getContentFormat());
            item.setMessageType(MESSAGE_TYPE_SYSTEM);
            item.setRecipientType(request.getRecipientType());
            item.setRecipientCount(recipientCount);
            item.setStatus(STATUS_DRAFT);
            item.setScheduledTime(request.getScheduledTime());
            item.setVersion(1L);
            item.setCreatorId(operatorId);
            item.setCreatorName(operatorName);
            item.setUpdaterId(operatorId);
            item.setUpdaterName(operatorName);
            item.setCreateTime(now);
            item.setUpdateTime(now);
            mapper.insert(item);
        }
        else
        {
            if (StrUtil.isBlank(request.getExpectedVersion()))
            {
                throw failure(HttpStatus.BAD_REQUEST, "NOTIFICATION_VERSION_REQUIRED", "版本号不能为空", "expectedVersion");
            }
            Long id = parseId(request.getId(), "id");
            long expectedVersion = parseVersion(request.getExpectedVersion());
            AidSystemNotification existing = requireLocked(id);
            requireStatus(existing, STATUS_DRAFT);
            requireVersion(existing, expectedVersion);
            item = new AidSystemNotification();
            item.setId(id);
            item.setTitle(request.getTitle());
            item.setSummary(request.getSummary());
            item.setContent(request.getContent());
            item.setContentFormat(request.getContentFormat());
            item.setRecipientType(request.getRecipientType());
            item.setRecipientCount(recipientCount);
            item.setScheduledTime(request.getScheduledTime());
            item.setUpdaterId(operatorId);
            item.setUpdaterName(operatorName);
            item.setUpdateTime(now);
            if (mapper.updateDraft(item, expectedVersion) != 1)
            {
                throw versionConflict();
            }
            mapper.deleteRecipients(id);
            item.setVersion(expectedVersion + 1L);
        }
        if (RECIPIENT_USERS.equals(item.getRecipientType()))
        {
            mapper.insertRecipients(item.getId(), recipientIds, now);
        }
        mapper.completeOperation(operation.getId(), item.getId(), item.getVersion(), now);
        return toAdminView(requireLocked(item.getId()));
    }

    @Override
    @Transactional
    public NotificationViews.Notification publish(Long operatorId, String operatorName,
                                                   NotificationAdminRequests.Action request)
    {
        return act(operatorId, operatorName, request, "PUBLISH", Action.PUBLISH);
    }

    @Override
    @Transactional
    public NotificationViews.Notification withdraw(Long operatorId, String operatorName,
                                                    NotificationAdminRequests.Action request)
    {
        return act(operatorId, operatorName, request, "WITHDRAW", Action.WITHDRAW);
    }

    @Override
    @Transactional
    public NotificationViews.Notification cancelSchedule(Long operatorId, String operatorName,
                                                          NotificationAdminRequests.Action request)
    {
        return act(operatorId, operatorName, request, "CANCEL_SCHEDULE", Action.CANCEL_SCHEDULE);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationViews.Page<NotificationViews.Recipient> recipients(
            NotificationAdminRequests.Recipients request)
    {
        int pageNum = pageNum(request.getPageNum());
        int pageSize = pageSize(request.getPageSize());
        String keyword = trimToNull(request.getKeyword());
        long total = mapper.countRecipientPage(keyword);
        long pageOffset = offset(pageNum, pageSize);
        List<NotificationViews.Recipient> items = (pageOffset >= total ? List.<NotificationRecipientRow>of()
                        : mapper.selectRecipientPage(keyword, pageOffset, pageSize)).stream()
                .map(this::toRecipientView).toList();
        return page(items, total, pageNum, pageSize, null);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationViews.Page<NotificationViews.UserNotification> userList(
            Long userId, NotificationUserRequests.ListRequest request)
    {
        int pageNum = pageNum(request.getPageNum());
        int pageSize = pageSize(request.getPageSize());
        long pageOffset = offset(pageNum, pageSize);
        AidNotificationUserState userState = mapper.selectUserState(userId);
        long readAllPublishSeq = userState == null ? 0L : value(userState.getReadAllPublishSeq());
        long total = mapper.countUserVisible(userId, request.getIsRead(), readAllPublishSeq);
        List<NotificationViews.UserNotification> items;
        if (pageOffset >= total)
        {
            items = List.of();
        }
        else
        {
            long candidateLimit = Math.min(total, Math.addExact(pageOffset, pageSize));
            items = mapper.selectUserPage(userId, request.getIsRead(), readAllPublishSeq,
                            pageOffset, pageSize, candidateLimit).stream()
                    .map(this::toUserView).toList();
        }
        NotificationViews.State state = stateQueryService.get(userId);
        return page(items, total, pageNum, pageSize, state.getStateVersion());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationViews.UserNotification userDetail(Long userId, String id)
    {
        NotificationQueryRow row = mapper.selectUserDetail(userId, parseId(id, "id"));
        if (row == null)
        {
            throw failure(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "通知不存在", "id");
        }
        return toUserView(row);
    }

    @Override
    public NotificationViews.State unread(Long userId)
    {
        return stateQueryService.get(userId);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public NotificationViews.State read(Long userId, String id)
    {
        Long notificationId = parseId(id, "id");
        lockUserState(userId);
        Date now = databaseNow();
        NotificationQueryRow visible = mapper.selectUserDetail(userId, notificationId);
        if (visible == null)
        {
            throw failure(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "通知不存在", "id");
        }
        if (!Boolean.TRUE.equals(visible.getRead()))
        {
            if (mapper.insertRead(userId, notificationId, now) == 1)
            {
                mapper.incrementReadRevision(userId, now);
                afterCommit(() -> realtimeService.publishUserStateChanged(userId));
            }
        }
        return stateQueryService.get(userId);
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public NotificationViews.State readAll(Long userId)
    {
        AidNotificationSequence sequence = requireSequenceLocked();
        long boundary = value(sequence.getPublishSeq());
        AidNotificationUserState state = lockUserState(userId);
        Date now = databaseNow();
        long currentBoundary = value(state.getReadAllPublishSeq());
        if (boundary > currentBoundary)
        {
            mapper.updateReadAllState(userId, boundary, now, now);
            afterCommit(() -> realtimeService.publishUserStateChanged(userId));
        }
        return stateQueryService.get(userId);
    }

    @Override
    public void publishDueScheduled()
    {
        Date now = databaseNow();
        int limit = Math.max(1, Math.min(scheduledBatchSize, 200));
        List<Long> dueIds = mapper.selectDueScheduledIds(now, limit);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        for (Long id : dueIds)
        {
            try
            {
                transaction.executeWithoutResult(status -> publishDueScheduledOne(id));
            }
            catch (RuntimeException error)
            {
                log.error("定时通知发布失败，稍后重试, notificationId={}", id, error);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveCUser(Long userId)
    {
        return userId != null && mapper.isActiveCUser(userId);
    }

    private void publishDueScheduledOne(Long id)
    {
        Date now = databaseNow();
        AidSystemNotification item = mapper.selectByIdForUpdate(id);
        if (item == null || !STATUS_SCHEDULED.equals(item.getStatus())
                || item.getScheduledTime() == null || item.getScheduledTime().after(now))
        {
            return;
        }
        if (!storedRecipientsValid(item))
        {
            mapper.cancelSchedule(item.getId(), item.getVersion(), 0L, "system", now);
            log.warn("定时通知收件人失效，已恢复草稿, notificationId={}", item.getId());
            return;
        }
        publishLocked(item, item.getUpdaterId(), item.getUpdaterName(), STATUS_SCHEDULED);
        afterCommit(() -> realtimeService.publishNotificationChanged(id, "NOTIFICATION_PUBLISHED"));
    }

    private NotificationViews.Notification act(Long operatorId, String operatorName,
                                                NotificationAdminRequests.Action request,
                                                String actionType, Action action)
    {
        request.setClientRequestId(StrUtil.trim(request.getClientRequestId()));
        Long id = parseId(request.getId(), "id");
        long expectedVersion = parseVersion(request.getExpectedVersion());
        String requestHash = hashRequest(request);
        AidNotificationOperation operation = reserveOperation(operatorId, actionType,
                request.getClientRequestId(), requestHash);
        if (operation.getCompleteTime() != null)
        {
            return replayCompletedOperation(operation);
        }
        AidSystemNotification item = requireLocked(id);
        requireVersion(item, expectedVersion);
        Date now = databaseNow();
        String signalType = null;
        switch (action)
        {
            case PUBLISH ->
            {
                requireStatus(item, STATUS_DRAFT);
                validateStoredRecipients(item);
                if (item.getScheduledTime() != null && item.getScheduledTime().after(now))
                {
                    if (mapper.scheduleNotification(id, expectedVersion, item.getScheduledTime(), operatorId,
                            operatorName, now) != 1)
                    {
                        throw versionConflict();
                    }
                }
                else
                {
                    publishLocked(item, operatorId, operatorName, STATUS_DRAFT);
                    signalType = "NOTIFICATION_PUBLISHED";
                }
            }
            case WITHDRAW ->
            {
                requireStatus(item, STATUS_PUBLISHED);
                AidNotificationSequence sequence = requireSequenceLocked();
                mapper.updateSequence(value(sequence.getPublishSeq()), value(sequence.getVisibilityRevision()) + 1L);
                if (mapper.withdrawNotification(id, expectedVersion, operatorId, operatorName, now) != 1)
                {
                    throw versionConflict();
                }
                signalType = "NOTIFICATION_WITHDRAWN";
            }
            case CANCEL_SCHEDULE ->
            {
                requireStatus(item, STATUS_SCHEDULED);
                if (mapper.cancelSchedule(id, expectedVersion, operatorId, operatorName, now) != 1)
                {
                    throw versionConflict();
                }
            }
        }
        AidSystemNotification updated = requireLocked(id);
        mapper.completeOperation(operation.getId(), id, updated.getVersion(), now);
        if (signalType != null)
        {
            String committedSignalType = signalType;
            afterCommit(() -> realtimeService.publishNotificationChanged(id, committedSignalType));
        }
        return toAdminView(updated);
    }

    private void publishLocked(AidSystemNotification item, Long operatorId, String operatorName,
                               String expectedStatus)
    {
        AidNotificationSequence sequence = requireSequenceLocked();
        Date publishTime = databaseNow();
        long publishSeq = value(sequence.getPublishSeq()) + 1L;
        long visibilityRevision = value(sequence.getVisibilityRevision()) + 1L;
        long recipientCount = RECIPIENT_ALL.equals(item.getRecipientType())
                ? mapper.countCurrentCUsers() : mapper.selectRecipientIds(item.getId()).size();
        if (mapper.publishNotification(item.getId(), item.getVersion(), expectedStatus, publishTime, publishSeq,
                recipientCount, operatorId, operatorName, publishTime) != 1)
        {
            throw versionConflict();
        }
        if (RECIPIENT_USERS.equals(item.getRecipientType()))
        {
            mapper.updateRecipientPublication(item.getId(), publishSeq, publishTime);
        }
        mapper.updateSequence(publishSeq, visibilityRevision);
    }

    private AidNotificationOperation reserveOperation(Long operatorId, String actionType,
                                                      String clientRequestId, String requestHash)
    {
        Date now = databaseNow();
        mapper.reserveOperation(operatorId, actionType, clientRequestId, requestHash, now);
        AidNotificationOperation operation = mapper.selectOperationForUpdate(operatorId, actionType, clientRequestId);
        if (operation == null)
        {
            throw failure(HttpStatus.ERROR, "NOTIFICATION_IDEMPOTENCY_UNAVAILABLE", "请求暂不可用", null);
        }
        if (!Objects.equals(operation.getRequestHash(), requestHash))
        {
            throw failure(HttpStatus.CONFLICT, "NOTIFICATION_IDEMPOTENCY_CONFLICT", "请求编号冲突", "clientRequestId");
        }
        return operation;
    }

    private NotificationViews.Notification replayCompletedOperation(AidNotificationOperation operation)
    {
        AidSystemNotification current = operation.getNotificationId() == null
                ? null : mapper.selectById(operation.getNotificationId());
        if (current == null || !Objects.equals(current.getVersion(), operation.getResponseVersion()))
        {
            throw failure(HttpStatus.CONFLICT, "NOTIFICATION_OPERATION_RESULT_CHANGED",
                    "原操作已完成，通知已变更，请刷新核实", "clientRequestId");
        }
        return toAdminView(current);
    }

    private List<Long> normalizeRecipients(String recipientType, List<String> suppliedIds)
    {
        List<String> values = suppliedIds == null ? List.of() : suppliedIds;
        if (RECIPIENT_ALL.equals(recipientType))
        {
            if (CollUtil.isNotEmpty(values))
            {
                throw failure(HttpStatus.BAD_REQUEST, "NOTIFICATION_RECIPIENTS_UNEXPECTED", "全员无需收件人", "recipientUserIds");
            }
            return List.of();
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (String value : values)
        {
            unique.add(parseId(value, "recipientUserIds"));
        }
        int maxRecipients = Math.max(1, Math.min(configuredMaxSelectedRecipients, 5000));
        if (unique.isEmpty() || unique.size() > maxRecipients)
        {
            throw failure(HttpStatus.BAD_REQUEST, "NOTIFICATION_RECIPIENTS_INVALID", "收件人数量无效", "recipientUserIds");
        }
        List<Long> ids = new ArrayList<>(unique);
        if (mapper.countValidRecipients(ids) != ids.size())
        {
            throw failure(HttpStatus.BAD_REQUEST, "NOTIFICATION_RECIPIENT_INVALID", "收件人已失效", "recipientUserIds");
        }
        return ids;
    }

    private void validateStoredRecipients(AidSystemNotification item)
    {
        if (!RECIPIENT_USERS.equals(item.getRecipientType()))
        {
            return;
        }
        List<Long> ids = mapper.selectRecipientIds(item.getId());
        if (ids.isEmpty() || mapper.countValidRecipients(ids) != ids.size())
        {
            throw failure(HttpStatus.BAD_REQUEST, "NOTIFICATION_RECIPIENT_INVALID", "收件人已失效", "recipientUserIds");
        }
    }

    private boolean storedRecipientsValid(AidSystemNotification item)
    {
        if (!RECIPIENT_USERS.equals(item.getRecipientType()))
        {
            return true;
        }
        List<Long> ids = mapper.selectRecipientIds(item.getId());
        return !ids.isEmpty() && mapper.countValidRecipients(ids) == ids.size();
    }

    private void normalizeSaveRequest(NotificationAdminRequests.Save request)
    {
        request.setTitle(StrUtil.trim(request.getTitle()));
        request.setContent(StrUtil.trim(request.getContent()));
        request.setContentFormat(StrUtil.blankToDefault(StrUtil.trim(request.getContentFormat()), FORMAT_MARKDOWN)
                .toUpperCase(Locale.ROOT));
        request.setRecipientType(StrUtil.trim(request.getRecipientType()).toUpperCase(Locale.ROOT));
        request.setClientRequestId(StrUtil.trim(request.getClientRequestId()));
        String summary = StrUtil.trim(request.getSummary());
        request.setSummary(StrUtil.isBlank(summary) ? generateSummary(request.getContent()) : summary);
        if (!FORMAT_MARKDOWN.equals(request.getContentFormat())
                && !FORMAT_PLAIN_TEXT.equals(request.getContentFormat()))
        {
            throw failure(HttpStatus.BAD_REQUEST, "NOTIFICATION_FORMAT_INVALID", "正文格式无效", "contentFormat");
        }
    }

    private String generateSummary(String content)
    {
        String plain = MARKDOWN_MARKERS.matcher(Objects.toString(content, "")).replaceAll(" ")
                .replaceAll("\\s+", " ").trim();
        return plain.length() <= SUMMARY_GENERATED_LENGTH
                ? plain : plain.substring(0, SUMMARY_GENERATED_LENGTH);
    }

    private NotificationViews.Notification toAdminView(AidSystemNotification item)
    {
        NotificationViews.Notification view = new NotificationViews.Notification();
        copyCommon(item, view);
        view.setContent(item.getContent());
        view.setContentFormat(item.getContentFormat());
        if (RECIPIENT_USERS.equals(item.getRecipientType()))
        {
            view.setRecipientUserIds(mapper.selectRecipientIds(item.getId()).stream().map(String::valueOf).toList());
        }
        else
        {
            view.setRecipientUserIds(List.of());
            view.setRecipientCount(mapper.countCurrentCUsers());
        }
        return view;
    }

    private NotificationViews.Notification toView(NotificationQueryRow row, Long currentAudience)
    {
        NotificationViews.Notification view = new NotificationViews.Notification();
        view.setId(string(row.getId()));
        view.setVersion(string(row.getVersion()));
        view.setStatus(row.getStatus());
        view.setTitle(row.getTitle());
        view.setSummary(row.getSummary());
        view.setContent(row.getContent());
        view.setContentFormat(row.getContentFormat());
        view.setMessageType(StrUtil.blankToDefault(row.getMessageType(), MESSAGE_TYPE_SYSTEM));
        view.setRecipientType(row.getRecipientType());
        view.setRecipientCount(RECIPIENT_ALL.equals(row.getRecipientType()) && currentAudience != null
                ? currentAudience : row.getRecipientCount());
        view.setScheduledTime(row.getScheduledTime());
        view.setPublishTime(row.getPublishTime());
        view.setCreatorId(string(row.getCreatorId()));
        view.setCreatorName(row.getCreatorName());
        view.setUpdaterId(string(row.getUpdaterId()));
        view.setUpdaterName(row.getUpdaterName());
        view.setCreateTime(row.getCreateTime());
        view.setUpdateTime(row.getUpdateTime());
        return view;
    }

    private NotificationViews.UserNotification toUserView(NotificationQueryRow row)
    {
        NotificationViews.UserNotification view = new NotificationViews.UserNotification();
        view.setId(string(row.getId()));
        view.setTitle(row.getTitle());
        view.setSummary(row.getSummary());
        view.setContent(row.getContent());
        view.setContentFormat(row.getContentFormat());
        view.setMessageType(StrUtil.blankToDefault(row.getMessageType(), MESSAGE_TYPE_SYSTEM));
        view.setPublishTime(row.getPublishTime());
        view.setIsRead(Boolean.TRUE.equals(row.getRead()));
        view.setReadTime(row.getReadTime());
        return view;
    }

    private void copyCommon(AidSystemNotification item, NotificationViews.Notification view)
    {
        view.setId(string(item.getId()));
        view.setVersion(string(item.getVersion()));
        view.setStatus(item.getStatus());
        view.setTitle(item.getTitle());
        view.setSummary(item.getSummary());
        view.setMessageType(item.getMessageType());
        view.setRecipientType(item.getRecipientType());
        view.setRecipientCount(item.getRecipientCount());
        view.setScheduledTime(item.getScheduledTime());
        view.setPublishTime(item.getPublishTime());
        view.setCreatorId(string(item.getCreatorId()));
        view.setCreatorName(item.getCreatorName());
        view.setUpdaterId(string(item.getUpdaterId()));
        view.setUpdaterName(item.getUpdaterName());
        view.setCreateTime(item.getCreateTime());
        view.setUpdateTime(item.getUpdateTime());
    }

    private NotificationViews.Recipient toRecipientView(NotificationRecipientRow row)
    {
        NotificationViews.Recipient view = new NotificationViews.Recipient();
        view.setUserId(String.valueOf(row.getUserId()));
        view.setUserName(row.getUserName());
        view.setNickName(row.getNickName());
        view.setAvatar(row.getAvatar());
        view.setStatus(row.getStatus());
        view.setCreateTime(row.getCreateTime());
        return view;
    }

    private AidSystemNotification requireLocked(Long id)
    {
        AidSystemNotification item = mapper.selectByIdForUpdate(id);
        if (item == null)
        {
            throw failure(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "通知不存在", "id");
        }
        return item;
    }

    private AidNotificationSequence requireSequenceLocked()
    {
        AidNotificationSequence sequence = mapper.selectSequenceForUpdate();
        if (sequence == null)
        {
            throw failure(HttpStatus.ERROR, "NOTIFICATION_SEQUENCE_MISSING", "通知服务异常", null);
        }
        return sequence;
    }

    private void requireStatus(AidSystemNotification item, String expectedStatus)
    {
        if (!Objects.equals(item.getStatus(), expectedStatus))
        {
            throw failure(HttpStatus.CONFLICT, "NOTIFICATION_STATUS_CONFLICT", "通知状态冲突", "status");
        }
    }

    private void requireVersion(AidSystemNotification item, long expectedVersion)
    {
        if (!Objects.equals(item.getVersion(), expectedVersion))
        {
            throw versionConflict();
        }
    }

    private ServiceException versionConflict()
    {
        return failure(HttpStatus.CONFLICT, "NOTIFICATION_VERSION_CONFLICT", "版本已变化", "expectedVersion");
    }

    private ServiceException failure(int httpCode, String errorCode, String message, String field)
    {
        log.info("notification request rejected, code={}, field={}", errorCode, field);
        ApiErrorDetails detail = new ApiErrorDetails();
        detail.setErrorCode(errorCode);
        detail.setField(field);
        detail.setReason(message);
        detail.setRetryable(httpCode >= 500);
        return new ServiceException(message, httpCode).setClientErrorData(detail);
    }

    private Date databaseNow()
    {
        Date now = mapper.selectDatabaseNow();
        if (now == null)
        {
            throw failure(HttpStatus.ERROR, "NOTIFICATION_CLOCK_UNAVAILABLE", "通知服务异常", null);
        }
        return now;
    }

    private int pageNum(Integer value)
    {
        return value == null ? 1 : Math.max(1, value);
    }

    private int pageSize(Integer value)
    {
        int configured = Math.max(1, Math.min(configuredMaxPageSize, 100));
        return Math.min(value == null ? 20 : Math.max(1, value), configured);
    }

    private long offset(int pageNum, int pageSize)
    {
        return (long) (pageNum - 1) * pageSize;
    }

    private <T> NotificationViews.Page<T> page(List<T> items, long total, int pageNum, int pageSize,
                                                String stateVersion)
    {
        NotificationViews.Page<T> result = new NotificationViews.Page<>();
        result.setItems(items);
        result.setTotal(total);
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setStateVersion(stateVersion);
        return result;
    }

    private long parseVersion(String value)
    {
        Long parsed = parseId(value, "expectedVersion");
        return parsed;
    }

    private Long parseId(String value, String field)
    {
        try
        {
            long parsed = Long.parseLong(value);
            if (parsed <= 0L)
            {
                throw new NumberFormatException("non-positive");
            }
            return parsed;
        }
        catch (RuntimeException error)
        {
            throw failure(HttpStatus.BAD_REQUEST, "NOTIFICATION_ID_INVALID", "编号格式错误", field);
        }
    }

    private String hashRequest(Object request)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(
                    JSON.toJSONString(request).getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException error)
        {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private String trimToNull(String value)
    {
        String trimmed = StrUtil.trim(value);
        return StrUtil.isBlank(trimmed) ? null : trimmed;
    }

    private String string(Long value)
    {
        return value == null ? null : String.valueOf(value);
    }

    private long value(Long value)
    {
        return value == null ? 0L : value;
    }

    private AidNotificationUserState lockUserState(Long userId)
    {
        mapper.ensureUserState(userId);
        AidNotificationUserState state = mapper.selectUserStateForUpdate(userId);
        if (state == null)
        {
            throw failure(HttpStatus.ERROR, "NOTIFICATION_USER_STATE_UNAVAILABLE", "通知阅读状态暂不可用", null);
        }
        return state;
    }

    private void afterCommit(Runnable action)
    {
        if (!TransactionSynchronizationManager.isSynchronizationActive())
        {
            runPostCommit(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            @Override
            public void afterCommit()
            {
                runPostCommit(action);
            }
        });
    }

    private void runPostCommit(Runnable action)
    {
        try
        {
            action.run();
        }
        catch (RuntimeException error)
        {
            log.warn("通知事务已提交，实时信号发送失败，将由权威同步修复", error);
        }
    }

    private enum Action
    {
        PUBLISH,
        WITHDRAW,
        CANCEL_SCHEDULE
    }
}
