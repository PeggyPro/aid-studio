package com.aid.generation.service;

import com.aid.generation.dto.GenerationRequests;
import com.aid.generation.vo.GenerationHistoryResponse;
import com.aid.generation.vo.GenerationSessionPage;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaTextGenerateRequest;

import java.util.List;
import java.util.Map;

/** 在既有三张聊天表中维护可选的通用生成会话。 */
public interface GenerationConversationStore {
    SessionState open(GenerationRequests.Session input, Long userId, Long workspaceId, Long projectId, Long episodeId,
                      String createKey, String nodeObjectId);
    /** 只读装配已有会话历史；sessionId 为空时不创建会话。 */
    SessionState preview(GenerationRequests.Session input, Long userId, Long workspaceId,
                         Long projectId, Long episodeId);
    ExistingTurn findExisting(SessionState session, String clientRequestId, String requestHash);
    Turn record(SessionState session, String clientRequestId, String requestHash, List<StoredMessage> inputMessages,
                String mediaType, MediaTaskResponse task);
    GenerationHistoryResponse history(Long sessionId, Long beforeSeq, Integer limit, Long userId);
    GenerationSessionPage sessions(Integer pageNum, Integer pageSize, Long userId);
    GenerationSessionPage nodeSessions(Long workspaceId, String nodeObjectId, Integer pageNum, Integer pageSize, Long userId);
    void complete(Long mediaTaskId, String content, String errorMessage);
    void complete(MediaTaskResponse task);
    Long activeTaskId(Long sessionId);
    List<Long> pendingTaskIds(int limit);

    record SessionState(Long sessionId, Long workspaceId, Long projectId, Long episodeId,
                        List<MediaTextGenerateRequest.TextMessageItem> history) {
        public static SessionState disabled(Long workspaceId, Long projectId, Long episodeId) {
            return new SessionState(null, workspaceId, projectId, episodeId, List.of());
        }
        public boolean enabled() { return sessionId != null; }
    }

    record Turn(Long userMessageId, Long assistantMessageId) {
        public static Turn disabled() { return new Turn(null, null); }
    }

    record ExistingTurn(Long assistantMessageId, Long mediaTaskId) { }

    record StoredMessage(String role, String content, String toolCallId, List<StoredPart> parts) { }

    record StoredPart(String type, String text, Long resourceId, String resourceRole,
                      String resourceUrl, String mimeType, Map<String, Object> metadata) { }
}
