package com.aid.context;

import com.aid.assistant.domain.AidAssistantSession;
import com.aid.runtime.domain.AidRuntimeEvent;
import com.aid.runtime.mapper.AidRuntimeEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Orders already-authorized immutable sources by the session's persisted event sequence, not string IDs. */
@Service
public class ConversationSourceTimeline {
    private final AidRuntimeEventMapper events;
    public ConversationSourceTimeline(AidRuntimeEventMapper events) { this.events = Objects.requireNonNull(events); }
    public List<ConversationContextPolicy.Source> order(AidAssistantSession session,
                                                       List<ConversationContextPolicy.Source> sources) {
        if (session == null || session.getId() == null || session.getLastEventSeq() == null || sources == null)
            throw unavailable();
        Map<String, ConversationContextPolicy.Source> wanted = new HashMap<>();
        for (var source : sources) if (wanted.putIfAbsent(key(source), source) != null) throw unavailable();
        Map<String, Long> order = new HashMap<>();
        long after = 0;
        while (order.size() < wanted.size()) {
            var page = events.selectList(new LambdaQueryWrapper<AidRuntimeEvent>()
                    .select(AidRuntimeEvent::getSessionId, AidRuntimeEvent::getRunId, AidRuntimeEvent::getSeq,
                            AidRuntimeEvent::getEventType, AidRuntimeEvent::getEntityId)
                    .eq(AidRuntimeEvent::getSessionId, session.getId()).gt(AidRuntimeEvent::getSeq, after)
                    .le(AidRuntimeEvent::getSeq, session.getLastEventSeq())
                    .in(AidRuntimeEvent::getEventType, "message.created", "message.queued", "input.submitted")
                    .orderByAsc(AidRuntimeEvent::getSeq).last("LIMIT 200"));
            for (var event : page) {
                if (!Objects.equals(session.getId(), event.getSessionId()) || event.getSeq() == null
                        || event.getSeq() <= after || event.getSeq() > session.getLastEventSeq()) throw unavailable();
                after = event.getSeq();
                boolean answer = "input.submitted".equals(event.getEventType());
                String key = (answer ? "answer:" : "message:") + event.getEntityId();
                var source = wanted.get(key);
                if (source == null) continue;
                if (answer && !Objects.equals(event.getRunId(), Long.valueOf(source.sourceId().split(":")[1])))
                    throw unavailable();
                order.putIfAbsent(key, event.getSeq());
            }
            if (page.size() < 200) break;
        }
        if (order.size() != wanted.size()) throw unavailable();
        return sources.stream().map(source -> new ConversationContextPolicy.Source(source.sourceId(),
                order.get(key(source)), source.kind(), source.text())).toList();
    }
    private static String key(ConversationContextPolicy.Source source) {
        if (source.kind() == ConversationContextPolicy.Kind.USER && source.sourceId().matches("message:[1-9][0-9]{0,18}"))
            return source.sourceId();
        if (source.kind() == ConversationContextPolicy.Kind.CONFIRMED_INPUT
                && source.sourceId().matches("answer:[1-9][0-9]{0,18}:[1-9][0-9]{0,18}"))
            return "answer:" + source.sourceId().split(":")[2];
        throw unavailable();
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("CONVERSATION_SOURCE_TIMELINE_UNAVAILABLE");
    }
}
