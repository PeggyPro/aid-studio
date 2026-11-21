package com.aid.canvas.publication.service;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.Set;

/** 发布媒体的技术属性投影；上游结果信封、地址及上传幂等信息不属于公开媒体元数据。 */
public final class CanvasPublicationMediaMetadata {
    private CanvasPublicationMediaMetadata() { }
    private static final Set<String> FIELDS = Set.of("width", "height", "durationSeconds", "fps", "format",
            "sizeBytes", "codec", "pageCount", "sampleRate", "channels", "sourceWidth", "sourceHeight",
            "index", "x", "y", "row", "col", "regionIndex");

    public static String project(String json) {
        if (json == null) return null;
        JSONObject source = JSON.parseObject(json), result = new JSONObject();
        JSONObject metadata = source.get("metadata") instanceof JSONObject nested ? nested : new JSONObject();
        for (String key : FIELDS) {
            Object value = source.containsKey(key) ? source.get(key) : metadata.get(key);
            if (value == null) continue;
            if (!(value instanceof String) && !(value instanceof Number number && Double.isFinite(number.doubleValue())))
                throw new ServiceException("媒体技术属性格式错误：" + key, 400);
            result.put(key, value);
        }
        if (!result.containsKey("durationSeconds") && source.get("durationMs") instanceof Number ms) {
            double duration = ms.doubleValue() / 1000D;
            if (!Double.isFinite(duration) || duration < 0) throw new ServiceException("媒体时长格式错误", 400);
            result.put("durationSeconds", duration);
        }
        return result.isEmpty() ? null : result.toJSONString();
    }
}
