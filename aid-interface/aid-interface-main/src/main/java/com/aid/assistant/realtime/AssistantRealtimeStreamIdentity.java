package com.aid.assistant.realtime;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONObject;
import java.util.Objects;

/** One identity for the origin archive and the existing WebSocket projection. */
final class AssistantRealtimeStreamIdentity {
    private AssistantRealtimeStreamIdentity() { }

    static String resolve(AssistantRealtimeSignal signal, JSONObject data) {
        if (StrUtil.isNotBlank(signal.getStreamId())) return signal.getStreamId();
        String owner = signal.getSkillRunId() == null
                ? "main:" + signal.getRunId() : "skill:" + signal.getSkillRunId();
        String streamType = StrUtil.blankToDefault(data.getString("streamType"),
                "REASONING_DELTA".equals(signal.getType()) ? "REASONING" : "TEXT");
        return owner + ":" + Objects.toString(signal.getStepId(), "run")
                + (signal.getSkillRunId() == null ? ":task:" + Objects.toString(signal.getMediaTaskId(), "unbound") : "")
                + ":" + streamType + ":"
                + StrUtil.blankToDefault(data.getString("artifactType"), "TEXT");
    }
}
