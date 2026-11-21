package com.aid.generation.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.aid.common.aid.json.JsonSafeId;
import com.aid.common.exception.ServiceException;
import com.aid.generation.domain.AidChatMessage;
import com.aid.generation.domain.AidChatMessagePart;
import com.aid.generation.domain.AidChatSession;
import com.aid.generation.dto.GenerationRequests;
import com.aid.generation.mapper.AidChatMessageMapper;
import com.aid.generation.mapper.AidChatMessagePartMapper;
import com.aid.generation.mapper.AidChatSessionMapper;
import com.aid.generation.service.GenerationConversationStore;
import com.aid.generation.service.GenerationResourceResolver;
import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.domain.CanvasEntities.CanvasObject;
import com.aid.canvas.domain.CanvasEntities.TaskBinding;
import com.aid.canvas.mapper.CanvasMapper;
import com.aid.canvas.service.CanvasPlainText;
import com.aid.canvas.service.CanvasResourceService;
import com.aid.generation.vo.GenerationHistoryResponse;
import com.aid.generation.vo.GenerationSessionPage;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 以短事务维护通用生成会话，流式正文只在终态写入一次。 */
@Service
@RequiredArgsConstructor
public class GenerationConversationStoreImpl implements GenerationConversationStore {
    private static final int HISTORY_LIMIT = 50;
    private static final int PAGE_LIMIT = 100;
    private final AidChatSessionMapper sessionMapper;
    private final AidChatMessageMapper messageMapper;
    private final AidChatMessagePartMapper partMapper;
    private final GenerationResourceResolver resourceResolver;
    private final CanvasMapper canvasMapper;
    private final CanvasResourceService canvasResourceService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SessionState open(GenerationRequests.Session input, Long userId, Long workspaceId,
                             Long projectId, Long episodeId, String createKey, String nodeObjectId) {
        if (input == null) return SessionState.disabled(workspaceId, projectId, episodeId);
        long expectedProject = zero(projectId);
        long expectedEpisode = zero(episodeId);
        AidChatSession session;
        if (input.getSessionId() == null) {
            if (StrUtil.isBlank(createKey)) throw new ServiceException("会话创建标识缺失");
            session = findCreatedSession(userId, createKey);
            if (session == null) {
                AidChatSession created = new AidChatSession();
                created.setUserId(userId);
                created.setSkillId(null);
                created.setProjectId(expectedProject);
                created.setEpisodeId(expectedEpisode);
                created.setWorkspaceId(workspaceId);
                created.setNodeObjectId(nodeObjectId);
                created.setSessionType("GENERATION");
                created.setClientSessionId(createKey);
                created.setTitle(StrUtil.blankToDefault(input.getTitle(), "新会话").trim());
                created.setBaseInputJson("{}");
                created.setReasoningEnabled(false);
                created.setShowReasoning(false);
                created.setLastMessageSeq(0L);
                created.setFavorite(false);
                created.setStatus("ACTIVE");
                created.setLastActiveTime(new Date());
                created.setDelFlag("0");
                fillCreate(created, userId);
                try {
                    if (sessionMapper.insert(created) != 1) throw new ServiceException("会话创建失败");
                    session = created;
                } catch (DuplicateKeyException duplicate) {
                    session = findCreatedSession(userId, createKey);
                    if (session == null) throw new ServiceException("会话创建处理中");
                }
            }
        } else {
            session = findGenerationSession(input.getSessionId(), userId);
        }
        requireScope(session, workspaceId, projectId, episodeId, nodeObjectId);
        Long storedProjectId = zero(session.getProjectId()) == 0L ? null : session.getProjectId();
        Long storedEpisodeId = zero(session.getEpisodeId()) == 0L ? null : session.getEpisodeId();
        resourceResolver.resolveProjectScope(session.getWorkspaceId(), storedProjectId, storedEpisodeId, userId);
        return new SessionState(session.getId(), session.getWorkspaceId(), storedProjectId, storedEpisodeId,
                loadHistory(session, userId));
    }

    @Override
    public SessionState preview(GenerationRequests.Session input, Long userId, Long workspaceId,
                                Long projectId, Long episodeId) {
        if (input == null || input.getSessionId() == null) {
            return SessionState.disabled(workspaceId, projectId, episodeId);
        }
        AidChatSession session = findGenerationSession(input.getSessionId(), userId);
        requirePreviewScope(session, workspaceId, projectId, episodeId);
        Long storedProjectId = zero(session.getProjectId()) == 0L ? null : session.getProjectId();
        Long storedEpisodeId = zero(session.getEpisodeId()) == 0L ? null : session.getEpisodeId();
        resourceResolver.resolveProjectScope(session.getWorkspaceId(), storedProjectId, storedEpisodeId, userId);
        return new SessionState(session.getId(), session.getWorkspaceId(), storedProjectId, storedEpisodeId,
                loadHistory(session, userId));
    }

    @Override
    public ExistingTurn findExisting(SessionState state, String clientRequestId, String requestHash) {
        if (state == null || !state.enabled()) return null;
        AidChatMessage first = messageMapper.selectOne(Wrappers.<AidChatMessage>lambdaQuery()
                .select(AidChatMessage::getSeqNo, AidChatMessage::getRequestHash)
                .eq(AidChatMessage::getSessionId, state.sessionId())
                .eq(AidChatMessage::getClientMessageId, clientRequestId)
                .eq(AidChatMessage::getDelFlag, "0").last("LIMIT 1"));
        if (first == null) return null;
        if (!Objects.equals(first.getRequestHash(), requestHash)) {
            throw new ServiceException("clientRequestId 已被不同请求使用");
        }
        AidChatMessage assistant = messageMapper.selectOne(Wrappers.<AidChatMessage>lambdaQuery()
                .select(AidChatMessage::getId, AidChatMessage::getMediaTaskId)
                .eq(AidChatMessage::getSessionId, state.sessionId())
                .gt(AidChatMessage::getSeqNo, first.getSeqNo())
                .eq(AidChatMessage::getRole, "ASSISTANT")
                .isNotNull(AidChatMessage::getMediaTaskId)
                .eq(AidChatMessage::getDelFlag, "0")
                .orderByAsc(AidChatMessage::getSeqNo).last("LIMIT 1"));
        return assistant == null ? null : new ExistingTurn(assistant.getId(), assistant.getMediaTaskId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Turn record(SessionState state, String clientRequestId, String requestHash,
                       List<StoredMessage> inputMessages,
                       String mediaType, MediaTaskResponse task) {
        if (state == null || !state.enabled()) return Turn.disabled();
        if (CollectionUtil.isEmpty(inputMessages)) throw new ServiceException("会话当前轮为空");
        if (inputMessages.size() > 20 || inputMessages.stream()
                .mapToInt(message -> message.parts() == null ? 0 : message.parts().size()).sum() > 256) {
            throw new ServiceException("会话单轮消息内容过多");
        }
        AidChatSession locked = sessionMapper.selectOne(Wrappers.<AidChatSession>lambdaQuery()
                .select(AidChatSession::getId, AidChatSession::getUserId, AidChatSession::getLastMessageSeq)
                .eq(AidChatSession::getId, state.sessionId()).eq(AidChatSession::getSessionType, "GENERATION")
                .eq(AidChatSession::getDelFlag, "0").last("FOR UPDATE"));
        if (locked == null) throw new ServiceException("会话不存在");
        ExistingTurn existing = findExisting(state, clientRequestId, requestHash);
        if (existing != null) return new Turn(null, existing.assistantMessageId());

        long seq = locked.getLastMessageSeq() == null ? 0L : locked.getLastMessageSeq();
        Long firstMessageId = null;
        for (int i = 0; i < inputMessages.size(); i++) {
            StoredMessage input = inputMessages.get(i);
            AidChatMessage message = message(state.sessionId(), ++seq,
                    StrUtil.blankToDefault(input.role(), "user").toUpperCase(), "SUCCEEDED", "TEXT",
                    null, i == 0 ? clientRequestId : null, i == 0 ? requestHash : null, input.content(), null,
                    input.toolCallId(), locked.getUserId());
            messageMapper.insert(message);
            if (firstMessageId == null) firstMessageId = message.getId();
            insertInputParts(message, input, locked.getUserId());
        }
        AidChatMessage assistant = message(state.sessionId(), ++seq, "ASSISTANT",
                task == null ? "PROCESSING" : task.getStatus(), mediaType,
                task == null ? null : task.getTaskId(), null, null,
                task == null ? null : resultContent(task), task == null ? null : task.getModelName(),
                null, locked.getUserId());
        try {
            messageMapper.insert(assistant);
        } catch (DuplicateKeyException duplicate) {
            throw new ServiceException("会话消息处理中");
        }
        if (task != null && StrUtil.isNotBlank(resultContent(task))) {
            insertResultPart(assistant, mediaType, resultContent(task), resultUrl(task), locked.getUserId());
        }
        Date now = new Date();
        sessionMapper.update(null, Wrappers.<AidChatSession>lambdaUpdate()
                .eq(AidChatSession::getId, state.sessionId())
                .set(AidChatSession::getLastMessageSeq, seq)
                .set(AidChatSession::getLastActiveTime, now)
                .set(AidChatSession::getUpdateBy, Long.toString(locked.getUserId()))
                .set(AidChatSession::getUpdateTime, now));
        return new Turn(firstMessageId, assistant.getId());
    }

    @Override
    public GenerationHistoryResponse history(Long sessionId, Long beforeSeq, Integer requestedLimit, Long userId) {
        AidChatSession session = findGenerationSession(sessionId, userId);
        resourceResolver.resolveProjectScope(session.getWorkspaceId(),
                zero(session.getProjectId()) == 0L ? null : session.getProjectId(),
                zero(session.getEpisodeId()) == 0L ? null : session.getEpisodeId(), userId);
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > PAGE_LIMIT) throw new ServiceException("分页数量错误");
        var query = Wrappers.<AidChatMessage>lambdaQuery()
                .select(AidChatMessage::getId, AidChatMessage::getSeqNo, AidChatMessage::getRole,
                        AidChatMessage::getStatus, AidChatMessage::getMediaType, AidChatMessage::getModelCode,
                        AidChatMessage::getContentText, AidChatMessage::getToolCallId,
                        AidChatMessage::getErrorMessage, AidChatMessage::getMediaTaskId)
                .eq(AidChatMessage::getSessionId, session.getId()).eq(AidChatMessage::getDelFlag, "0")
                .lt(beforeSeq != null, AidChatMessage::getSeqNo, beforeSeq)
                .orderByDesc(AidChatMessage::getSeqNo).last("LIMIT " + (limit + 1));
        List<AidChatMessage> rows = messageMapper.selectList(query);
        boolean hasMore = rows.size() > limit;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, limit));
        Collections.reverse(rows);
        Map<Long, List<AidChatMessagePart>> parts = loadParts(rows);
        reauthorizeParts(session, parts, userId);
        List<GenerationHistoryResponse.Message> items = new ArrayList<>(rows.size());
        for (AidChatMessage row : rows) {
            List<AidChatMessagePart> messageParts = parts.getOrDefault(row.getId(), List.of());
            GenerationHistoryResponse.Message view = historyMessage(row, messageParts);
            if (session.getNodeObjectId() != null && "ASSISTANT".equals(row.getRole())
                    && "TEXT".equals(row.getMediaType())) {
                String completeText = messageParts.stream().filter(part -> "RESULT".equals(part.getResourceRole())
                        && "TEXT".equals(part.getPartType())).map(AidChatMessagePart::getTextContent)
                        .filter(Objects::nonNull).findFirst().orElse(row.getContentText());
                TaskBinding binding = row.getMediaTaskId() == null ? null : canvasMapper.selectTaskBinding(row.getMediaTaskId());
                String format = binding != null && Objects.equals(binding.getWorkspaceId(), session.getWorkspaceId())
                        && Objects.equals(binding.getObjectId(), session.getNodeObjectId())
                        ? binding.getResultFormat() : "TEXT";
                if (completeText != null) {
                    String plain = CanvasPlainText.normalize(completeText, format);
                    view.setContent(plain);
                    if (view.getParts() != null) view.getParts().stream()
                            .filter(part -> "RESULT".equals(part.getResourceRole()) && "TEXT".equals(part.getType()))
                            .forEach(part -> part.setText(plain));
                }
            }
            items.add(view);
        }
        Long nextCursor = hasMore && !rows.isEmpty() ? rows.get(0).getSeqNo() : null;
        return GenerationHistoryResponse.builder().sessionId(JsonSafeId.value(session.getId()))
                .workspaceId(JsonSafeId.value(session.getWorkspaceId()))
                .nodeObjectId(session.getNodeObjectId())
                .projectId(JsonSafeId.value(zero(session.getProjectId()) == 0L ? null : session.getProjectId()))
                .episodeId(JsonSafeId.value(zero(session.getEpisodeId()) == 0L ? null : session.getEpisodeId()))
                .title(session.getTitle())
                .items(items).nextCursor(JsonSafeId.value(nextCursor)).hasMore(hasMore).build();
    }

    @Override
    public GenerationSessionPage sessions(Integer requestedPageNum, Integer requestedPageSize, Long userId) {
        return sessionPage(null, null, requestedPageNum, requestedPageSize, userId);
    }

    @Override
    public GenerationSessionPage nodeSessions(Long workspaceId, String nodeObjectId,
            Integer requestedPageNum, Integer requestedPageSize, Long userId) {
        resourceResolver.resolveProjectScope(workspaceId, null, null, userId);
        CanvasObject node = canvasMapper.selectCanvasObject(workspaceId, nodeObjectId);
        if (node == null || node.getDeleted() == 1 || !"NODE".equals(node.getObjectType())) {
            throw new ServiceException("节点不存在或无权访问");
        }
        return sessionPage(workspaceId, nodeObjectId, requestedPageNum, requestedPageSize, userId);
    }

    private GenerationSessionPage sessionPage(Long workspaceId, String nodeObjectId,
            Integer requestedPageNum, Integer requestedPageSize, Long userId) {
        int pageNum = requestedPageNum == null ? 1 : requestedPageNum;
        int pageSize = requestedPageSize == null ? 20 : requestedPageSize;
        if (pageNum < 1 || pageSize < 1 || pageSize > 100) throw new ServiceException("分页参数错误");
        Page<AidChatSession> page = sessionMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<AidChatSession>lambdaQuery()
                        .select(AidChatSession::getId, AidChatSession::getWorkspaceId, AidChatSession::getNodeObjectId,
                                AidChatSession::getProjectId, AidChatSession::getEpisodeId,
                                AidChatSession::getTitle, AidChatSession::getLastMessageSeq,
                                AidChatSession::getLastActiveTime)
                        .eq(AidChatSession::getUserId, userId)
                        .eq(workspaceId != null, AidChatSession::getWorkspaceId, workspaceId)
                        .eq(nodeObjectId != null, AidChatSession::getNodeObjectId, nodeObjectId)
                        .eq(AidChatSession::getSessionType, "GENERATION")
                        .eq(AidChatSession::getStatus, "ACTIVE")
                        .eq(AidChatSession::getDelFlag, "0")
                        .orderByDesc(AidChatSession::getLastActiveTime, AidChatSession::getId));
        List<GenerationSessionPage.Item> items = page.getRecords().stream().map(session ->
                GenerationSessionPage.Item.builder()
                        .sessionId(JsonSafeId.value(session.getId()))
                        .workspaceId(JsonSafeId.value(session.getWorkspaceId()))
                        .nodeObjectId(session.getNodeObjectId())
                        .projectId(JsonSafeId.value(zero(session.getProjectId()) == 0L ? null : session.getProjectId()))
                        .episodeId(JsonSafeId.value(zero(session.getEpisodeId()) == 0L ? null : session.getEpisodeId()))
                        .title(session.getTitle()).lastMessageSeq(JsonSafeId.value(session.getLastMessageSeq()))
                        .lastActiveTime(session.getLastActiveTime()).build()).toList();
        return GenerationSessionPage.builder().items(items).total(page.getTotal())
                .pageNum(pageNum).pageSize(pageSize).build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long mediaTaskId, String content, String errorMessage) {
        complete(mediaTaskId, "TEXT", StrUtil.isBlank(errorMessage) ? "SUCCEEDED" : "FAILED",
                content, null, errorMessage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(MediaTaskResponse task) {
        if (task == null || task.getTaskId() == null) return;
        String content = "TEXT".equals(task.getMediaType()) ? task.getTextContent() : resultUrl(task);
        complete(task.getTaskId(), task.getMediaType(), task.getStatus(), content,
                "TEXT".equals(task.getMediaType()) ? null : resultUrl(task), task.getErrorMessage());
    }

    @Override
    public Long activeTaskId(Long sessionId) {
        if (sessionId == null) return null;
        AidChatMessage message = messageMapper.selectOne(Wrappers.<AidChatMessage>lambdaQuery()
                .select(AidChatMessage::getMediaTaskId)
                .eq(AidChatMessage::getSessionId, sessionId)
                .eq(AidChatMessage::getRole, "ASSISTANT")
                .isNotNull(AidChatMessage::getMediaTaskId)
                .notIn(AidChatMessage::getStatus, "SUCCEEDED", "FAILED", "CANCELLED")
                .eq(AidChatMessage::getDelFlag, "0")
                .orderByDesc(AidChatMessage::getSeqNo)
                .last("LIMIT 1"));
        return message == null ? null : message.getMediaTaskId();
    }

    @Override
    public List<Long> pendingTaskIds(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return messageMapper.selectConversationReconcileTaskIds(safeLimit);
    }

    private void complete(Long mediaTaskId, String mediaType, String status, String content,
                          String resourceUrl, String errorMessage) {
        if (mediaTaskId == null) return;
        List<AidChatMessage> messages = messageMapper.selectList(Wrappers.<AidChatMessage>lambdaQuery()
                .select(AidChatMessage::getId, AidChatMessage::getMediaType, AidChatMessage::getCreateBy)
                .eq(AidChatMessage::getMediaTaskId, mediaTaskId).eq(AidChatMessage::getRole, "ASSISTANT")
                .eq(AidChatMessage::getDelFlag, "0").last("FOR UPDATE"));
        if (messages.isEmpty()) return;
        for (AidChatMessage message : messages) {
            completeMessage(message, mediaType, status, content, resourceUrl, errorMessage);
        }
    }

    private void completeMessage(AidChatMessage message, String mediaType, String status, String content,
                                 String resourceUrl, String errorMessage) {
        boolean success = "SUCCEEDED".equals(status);
        messageMapper.update(null, Wrappers.<AidChatMessage>lambdaUpdate()
                .eq(AidChatMessage::getId, message.getId())
                .set(AidChatMessage::getStatus, status)
                .set(AidChatMessage::getContentText, limit(content, 1000))
                .set(AidChatMessage::getErrorMessage, limit(errorMessage, 500))
                .set(AidChatMessage::getUpdateBy, message.getCreateBy())
                .set(AidChatMessage::getUpdateTime, new Date()));
        if (success && StrUtil.isNotBlank(content)) {
            long partCount = partMapper.selectCount(Wrappers.<AidChatMessagePart>lambdaQuery()
                    .eq(AidChatMessagePart::getMessageId, message.getId()).eq(AidChatMessagePart::getDelFlag, "0"));
            if (partCount == 0) {
                insertResultPart(message, StrUtil.blankToDefault(mediaType, "TEXT"), content, resourceUrl,
                        parseUserId(message.getCreateBy()));
            } else if (!"TEXT".equals(mediaType) && StrUtil.isNotBlank(resourceUrl)) {
                partMapper.update(null, Wrappers.<AidChatMessagePart>lambdaUpdate()
                        .eq(AidChatMessagePart::getMessageId, message.getId())
                        .eq(AidChatMessagePart::getResourceRole, "RESULT")
                        .eq(AidChatMessagePart::getDelFlag, "0")
                        .set(AidChatMessagePart::getResourceUrl, resourceUrl)
                        .set(AidChatMessagePart::getUpdateBy, message.getCreateBy())
                        .set(AidChatMessagePart::getUpdateTime, new Date()));
            }
        }
    }

    private List<MediaTextGenerateRequest.TextMessageItem> loadHistory(AidChatSession session, Long userId) {
        List<AidChatMessage> rows = messageMapper.selectList(Wrappers.<AidChatMessage>lambdaQuery()
                .select(AidChatMessage::getId, AidChatMessage::getRole, AidChatMessage::getContentText,
                        AidChatMessage::getToolCallId, AidChatMessage::getSeqNo)
                .eq(AidChatMessage::getSessionId, session.getId()).eq(AidChatMessage::getStatus, "SUCCEEDED")
                .eq(AidChatMessage::getDelFlag, "0").in(AidChatMessage::getRole, "USER", "ASSISTANT", "TOOL")
                .orderByDesc(AidChatMessage::getSeqNo).last("LIMIT " + HISTORY_LIMIT));
        if (CollectionUtil.isEmpty(rows)) return List.of();
        Collections.reverse(rows);
        Map<Long, List<AidChatMessagePart>> parts = loadParts(rows);
        reauthorizeParts(session, parts, userId);
        List<MediaTextGenerateRequest.TextMessageItem> result = new ArrayList<>(rows.size());
        for (AidChatMessage row : rows) {
            List<AidChatMessagePart> messageParts = parts.getOrDefault(row.getId(), List.of());
            if (StrUtil.isBlank(row.getContentText()) && messageParts.isEmpty()) continue;
            MediaTextGenerateRequest.TextMessageItem item = new MediaTextGenerateRequest.TextMessageItem();
            item.setRole(row.getRole().toLowerCase());
            item.setToolCallId(row.getToolCallId());
            if (messageParts.isEmpty()) item.setContent(row.getContentText());
            else item.setParts(messageParts.stream().map(this::providerPart).toList());
            result.add(item);
        }
        return result;
    }

    private Map<Long, List<AidChatMessagePart>> loadParts(List<AidChatMessage> messages) {
        if (CollectionUtil.isEmpty(messages)) return Map.of();
        List<Long> ids = messages.stream().map(AidChatMessage::getId).toList();
        List<AidChatMessagePart> rows = partMapper.selectList(Wrappers.<AidChatMessagePart>lambdaQuery()
                .select(AidChatMessagePart::getId, AidChatMessagePart::getMessageId,
                        AidChatMessagePart::getPartSeq, AidChatMessagePart::getPartType,
                        AidChatMessagePart::getTextContent, AidChatMessagePart::getResourceId,
                        AidChatMessagePart::getResourceRole, AidChatMessagePart::getResourceUrl,
                        AidChatMessagePart::getMimeType, AidChatMessagePart::getMetadataJson)
                .in(AidChatMessagePart::getMessageId, ids).eq(AidChatMessagePart::getDelFlag, "0")
                .orderByAsc(AidChatMessagePart::getMessageId, AidChatMessagePart::getPartSeq));
        Map<Long, List<AidChatMessagePart>> result = new LinkedHashMap<>();
        for (AidChatMessagePart row : rows) {
            result.computeIfAbsent(row.getMessageId(), ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }

    private void reauthorizeParts(AidChatSession session, Map<Long, List<AidChatMessagePart>> parts,
                                  Long userId) {
        List<Long> resourceIds = parts.values().stream().flatMap(List::stream)
                .map(AidChatMessagePart::getResourceId).filter(Objects::nonNull).distinct().toList();
        if (resourceIds.isEmpty()) return;
        if (session.getWorkspaceId() == null) throw new ServiceException("会话资源范围缺失");
        List<CanvasResource> resources = canvasResourceService.requireResources(
                session.getWorkspaceId(), resourceIds, userId);
        Map<Long, CanvasResource> indexed = new LinkedHashMap<>();
        resources.forEach(resource -> indexed.put(resource.getId(), resource));
        for (List<AidChatMessagePart> messageParts : parts.values()) {
            for (AidChatMessagePart part : messageParts) {
                if (part.getResourceId() == null) continue;
                CanvasResource current = indexed.get(part.getResourceId());
                if (current == null) throw new ServiceException("会话素材已失效或无权访问");
                part.setResourceUrl(current.getStorageUrl());
                if (current.getFileSize() != null) {
                    JSONObject currentMetadata = metadata(part.getMetadataJson());
                    currentMetadata.put("sizeBytes", current.getFileSize());
                    part.setMetadataJson(currentMetadata.toJSONString());
                }
            }
        }
    }

    private MediaTextGenerateRequest.TextContentPart providerPart(AidChatMessagePart source) {
        MediaTextGenerateRequest.TextContentPart target = new MediaTextGenerateRequest.TextContentPart();
        target.setType(source.getPartType().toLowerCase());
        target.setText(source.getTextContent());
        target.setUrl(source.getResourceUrl());
        target.setMimeType(source.getMimeType());
        JSONObject metadata = metadata(source.getMetadataJson());
        target.setDetail(metadata.getString("detail"));
        target.setMediaResolution(metadata.getString("mediaResolution"));
        target.setFps(metadata.getDouble("fps"));
        target.setDurationSeconds(metadata.getDouble("durationSeconds"));
        target.setSizeBytes(metadata.getLong("sizeBytes"));
        target.setPageCount(metadata.getInteger("pageCount"));
        target.setWidth(metadata.getInteger("width"));
        target.setHeight(metadata.getInteger("height"));
        return target;
    }

    private GenerationHistoryResponse.Message historyMessage(AidChatMessage source,
                                                              List<AidChatMessagePart> parts) {
        return GenerationHistoryResponse.Message.builder()
                .messageId(JsonSafeId.value(source.getId())).seqNo(JsonSafeId.value(source.getSeqNo()))
                .role(source.getRole()).status(source.getStatus()).mediaType(source.getMediaType())
                .modelCode(source.getModelCode()).content(source.getContentText())
                .toolCallId(source.getToolCallId()).errorMessage(source.getErrorMessage())
                .mediaTaskId(JsonSafeId.value(source.getMediaTaskId()))
                .parts(parts.stream().map(this::historyPart).toList()).build();
    }

    private GenerationHistoryResponse.Part historyPart(AidChatMessagePart source) {
        return GenerationHistoryResponse.Part.builder()
                .partId(JsonSafeId.value(source.getId())).partSeq(source.getPartSeq())
                .type(source.getPartType()).text(source.getTextContent())
                .resourceId(JsonSafeId.value(source.getResourceId())).resourceRole(source.getResourceRole())
                .resourceUrl(source.getResourceUrl()).mimeType(source.getMimeType())
                .metadata(metadata(source.getMetadataJson())).build();
    }

    private AidChatSession findCreatedSession(Long userId, String createKey) {
        return sessionMapper.selectOne(Wrappers.<AidChatSession>lambdaQuery()
                .select(AidChatSession::getId, AidChatSession::getUserId, AidChatSession::getProjectId,
                        AidChatSession::getEpisodeId, AidChatSession::getWorkspaceId, AidChatSession::getNodeObjectId,
                        AidChatSession::getSessionType,
                        AidChatSession::getTitle, AidChatSession::getStatus, AidChatSession::getDelFlag)
                .eq(AidChatSession::getUserId, userId).eq(AidChatSession::getSessionType, "GENERATION")
                .eq(AidChatSession::getClientSessionId, createKey).eq(AidChatSession::getDelFlag, "0")
                .last("LIMIT 1"));
    }

    private AidChatSession findGenerationSession(Long sessionId, Long userId) {
        AidChatSession session = sessionMapper.selectOne(Wrappers.<AidChatSession>lambdaQuery()
                .select(AidChatSession::getId, AidChatSession::getUserId, AidChatSession::getProjectId,
                        AidChatSession::getEpisodeId, AidChatSession::getWorkspaceId, AidChatSession::getNodeObjectId,
                        AidChatSession::getSessionType,
                        AidChatSession::getTitle, AidChatSession::getStatus, AidChatSession::getDelFlag)
                .eq(AidChatSession::getId, sessionId).eq(AidChatSession::getUserId, userId)
                .eq(AidChatSession::getSessionType, "GENERATION").eq(AidChatSession::getDelFlag, "0"));
        if (session == null || !"ACTIVE".equals(session.getStatus())) throw new ServiceException("会话不存在或已关闭");
        return session;
    }

    private static void requireScope(AidChatSession session, Long workspaceId, Long projectId, Long episodeId,
                                     String nodeObjectId) {
        if (session == null || !"GENERATION".equals(session.getSessionType())
                || !"ACTIVE".equals(session.getStatus())) throw new ServiceException("会话不存在或已关闭");
        if (workspaceId != null && !Objects.equals(session.getWorkspaceId(), workspaceId)) {
            throw new ServiceException("会话工作区不一致");
        }
        if (!Objects.equals(session.getNodeObjectId(), nodeObjectId)) {
            throw new ServiceException("会话节点不一致");
        }
        if (projectId != null && !Objects.equals(zero(session.getProjectId()), zero(projectId))) {
            throw new ServiceException("会话项目不一致");
        }
        if (episodeId != null && !Objects.equals(zero(session.getEpisodeId()), zero(episodeId))) {
            throw new ServiceException("会话剧集不一致");
        }
    }

    private static void requirePreviewScope(AidChatSession session, Long workspaceId,
                                            Long projectId, Long episodeId) {
        if (session == null || !"GENERATION".equals(session.getSessionType())
                || !"ACTIVE".equals(session.getStatus())) throw new ServiceException("会话不存在或已关闭");
        if (workspaceId != null && !Objects.equals(session.getWorkspaceId(), workspaceId)) {
            throw new ServiceException("会话工作区不一致");
        }
        if (projectId != null && !Objects.equals(zero(session.getProjectId()), zero(projectId))) {
            throw new ServiceException("会话项目不一致");
        }
        if (episodeId != null && !Objects.equals(zero(session.getEpisodeId()), zero(episodeId))) {
            throw new ServiceException("会话剧集不一致");
        }
    }

    private AidChatMessage message(Long sessionId, long seq, String role, String status,
                                   String mediaType, Long taskId, String clientMessageId, String requestHash,
                                   String content, String modelCode, String toolCallId, Long userId) {
        AidChatMessage message = new AidChatMessage();
        message.setSessionId(sessionId);
        message.setSeqNo(seq);
        message.setRole(role);
        message.setStatus(status);
        message.setMediaType(mediaType);
        message.setMediaTaskId(taskId);
        message.setClientMessageId(clientMessageId);
        message.setRequestHash(requestHash);
        message.setContentText(limit(content, 1000));
        message.setModelCode(modelCode);
        message.setToolCallId(toolCallId);
        message.setContextAnchor(false);
        message.setDelFlag("0");
        fillCreate(message, userId);
        return message;
    }

    private void insertInputParts(AidChatMessage message, StoredMessage input, Long userId) {
        int seq = 0;
        if (StrUtil.isNotBlank(input.content())) {
            insertPart(message, ++seq, new StoredPart("TEXT", input.content(), null,
                    null, null, null, Map.of()), userId);
        }
        if (CollectionUtil.isNotEmpty(input.parts())) {
            for (StoredPart part : input.parts()) insertPart(message, ++seq, part, userId);
        }
    }

    private void insertPart(AidChatMessage message, int seq, StoredPart source, Long userId) {
        AidChatMessagePart part = new AidChatMessagePart();
        part.setMessageId(message.getId());
        part.setPartSeq(seq);
        part.setPartType(source.type());
        part.setSlotName("content");
        part.setTextContent(source.text());
        part.setResourceId(source.resourceId());
        part.setResourceRole(source.resourceRole());
        part.setResourceUrl(source.resourceUrl());
        part.setMimeType(source.mimeType());
        part.setMetadataJson(source.metadata() == null || source.metadata().isEmpty()
                ? null : JSON.toJSONString(source.metadata()));
        part.setContextScope("SESSION");
        part.setDelFlag("0");
        fillCreate(part, userId);
        partMapper.insert(part);
    }

    private void insertResultPart(AidChatMessage message, String type, String content,
                                  String resourceUrl, Long userId) {
        if (StrUtil.isBlank(content) && StrUtil.isBlank(resourceUrl)) return;
        insertPart(message, 1, new StoredPart(type, "TEXT".equals(type) ? content : null,
                null, "RESULT", resourceUrl, null, Map.of()), userId);
    }

    private static JSONObject metadata(String json) {
        if (StrUtil.isBlank(json)) return new JSONObject();
        try { return JSON.parseObject(json); }
        catch (RuntimeException ignored) { return new JSONObject(); }
    }

    private static String resultContent(MediaTaskResponse task) {
        if (task == null) return null;
        if ("TEXT".equals(task.getMediaType())) return task.getTextContent();
        return resultUrl(task);
    }

    private static String resultUrl(MediaTaskResponse task) {
        if (task == null) return null;
        return StrUtil.isNotBlank(task.getOssUrl()) ? task.getOssUrl() : task.getOriginUrl();
    }

    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static long zero(Long value) {
        return value == null ? 0L : value;
    }

    private static Long parseUserId(String value) {
        try { return value == null ? 0L : Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private static void fillCreate(com.aid.common.core.domain.BaseEntity entity, Long userId) {
        Date now = new Date();
        entity.setCreateBy(Long.toString(userId == null ? 0L : userId));
        entity.setCreateTime(now);
        entity.setUpdateBy(entity.getCreateBy());
        entity.setUpdateTime(now);
    }
}
