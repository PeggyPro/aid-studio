package com.aid.canvas.service;

import com.aid.aid.domain.media.AidMediaTask;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/** 画布图片编辑结果保护门禁；原始上游地址只作为平台后处理检查点。 */
public final class CanvasImageEditTaskGuard {
    private CanvasImageEditTaskGuard() { }

    public static boolean isProtectionPending(AidMediaTask task) {
        return task != null && isProtectionPending(
                task.getStatus(), task.getOssUrl(), task.getRequestJson());
    }

    public static boolean isProtectionPending(String status, String ossUrl, String requestJson) {
        if (!"SUCCEEDED".equals(status) || !blank(ossUrl) || blank(requestJson)) {
            return false;
        }
        try {
            JSONObject request = JSON.parseObject(requestJson);
            String capabilityCode = request == null ? null : request.getString("capabilityCode");
            return "image_inpainting".equalsIgnoreCase(capabilityCode)
                    || "image_outpainting".equalsIgnoreCase(capabilityCode)
                    || "image_layer_decomposition".equalsIgnoreCase(capabilityCode);
        } catch (RuntimeException ignored) {
            // 明确出现保护能力编码但历史快照无法解析时保持封闭，避免原始结果泄露。
            return requestJson.contains("image_inpainting") || requestJson.contains("image_outpainting")
                    || requestJson.contains("image_layer_decomposition");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
