package com.aid.skill.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import com.aid.aid.domain.media.AidMediaTask;
import com.aid.aid.mapper.AidMediaTaskMapper;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaTaskResponse;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.enums.MediaTaskStatus;
import com.aid.media.provider.StructuredOutputSupport;
import com.aid.media.provider.TextReasoningOptionsResolver;
import com.aid.media.service.IMediaGenerationService;
import com.aid.media.service.VerifiedMediaMetadataService;
import com.aid.skill.domain.AidSkill;
import com.aid.skill.domain.AidSkillVersion;
import com.aid.skill.mapper.AidSkillMapper;
import com.aid.skill.mapper.AidSkillVersionMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StyleAnalysisService {
    public static final String SKILL_CODE = "style_reference_analysis";
    private static final String TASK_TYPE = "STYLE_REFERENCE_ANALYSIS";
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final Map<String, Integer> FIELD_LIMITS = Map.of(
            "assetName", 100, "personalityDesc", 500, "promptText", 4000, "remark", 500);
    private static final String OUTPUT_INSTRUCTIONS = """
            只输出一个 JSON 对象。四个字段都必填，值为非空字符串，不得增加字段或 Markdown。
            assetName 是风格名称，最多100字。personalityDesc 是特征说明，最多500字。
            promptText 是供场景、角色、道具共用的可复用生成提示词，最多4000字。
            remark 是使用建议，最多500字。
            输出示例：
            {"assetName":"柔光水彩","personalityDesc":"低饱和，纸纹细腻，边缘自然晕染","promptText":"低饱和水彩插画，透明色层叠加，自然晕染，细腻纸纹，柔和漫射光","remark":"适用于统一角色、道具和场景的画风"}
            """;

    private final AidSkillMapper skillMapper;
    private final AidSkillVersionMapper versionMapper;
    private final AidMediaTaskMapper taskMapper;
    private final IMediaGenerationService mediaGenerationService;
    private final VerifiedMediaMetadataService metadata;
    private final RedissonClient redissonClient;

    public AnalysisStatus submit(String imageUrl, String requestId, Long userId) {
        if (!metadata.isConfiguredStorageUrl(imageUrl)) {
            throw new ServiceException("请上传本站风格图片");
        }
        RLock lock = redissonClient.getLock("style-analysis:submit:" + userId);
        try {
            if (!lock.tryLock(5, TimeUnit.SECONDS)) {
                throw new ServiceException("风格解析正在提交，请稍后查看进度", 409);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ServiceException("风格解析提交已中断，请稍后重试");
        }
        try {
            return submitForUser(imageUrl, requestId, userId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private AnalysisStatus submitForUser(String imageUrl, String requestId, Long userId) {
        String callId = DigestUtil.sha256Hex(TASK_TYPE + "|" + userId + "|" + requestId + "|" + imageUrl);
        AidMediaTask previous = taskMapper.selectOne(Wrappers.<AidMediaTask>lambdaQuery()
                .select(AidMediaTask::getId)
                .eq(AidMediaTask::getUserId, userId)
                .eq(AidMediaTask::getBizTaskType, TASK_TYPE)
                .eq(AidMediaTask::getIdempotencyKey, callId)
                .last("LIMIT 1"));
        if (previous != null) {
            return status(previous.getId(), userId);
        }
        if (findActiveTask(userId) != null) {
            throw new ServiceException("已有风格正在解析，请等待完成", 409);
        }
        var image = metadata.inspect(imageUrl, "image");
        if (image.sizeBytes() > MAX_IMAGE_BYTES || !Set.of("jpg", "jpeg", "png").contains(image.format())) {
            throw new ServiceException("请上传不超过10MB的JPG或PNG图片");
        }
        AidSkill skill = skillMapper.selectOne(Wrappers.<AidSkill>lambdaQuery()
                .eq(AidSkill::getSkillCode, SKILL_CODE)
                .eq(AidSkill::getDelFlag, "0")
                .eq(AidSkill::getStatus, "0"));
        AidSkillVersion version = skill == null || skill.getCurrentVersionId() == null
                ? null : versionMapper.selectById(skill.getCurrentVersionId());
        String prompt = version == null ? (skill == null ? null : skill.getSystemPrompt()) : version.getSystemPrompt();
        String model = version == null ? (skill == null ? null : skill.getModelCode()) : version.getModelCode();
        if (prompt == null || prompt.isBlank() || model == null || model.isBlank()) {
            throw new ServiceException("风格解析暂未配置，请手动填写");
        }

        MediaTextGenerateRequest.TextContentPart text = new MediaTextGenerateRequest.TextContentPart();
        text.setType("text");
        // 规则与图像放在同一条视觉请求，兼容只读取图片所在消息的视觉渠道。
        text.setText(prompt + "\n\n" + OUTPUT_INSTRUCTIONS
                + "\n分析这张参考图，严格使用示例的四个字段名，不要增加、改名或翻译字段。");
        MediaTextGenerateRequest.TextContentPart picture = new MediaTextGenerateRequest.TextContentPart();
        picture.setType("image");
        picture.setUrl(imageUrl);
        MediaTextGenerateRequest.TextMessageItem user = new MediaTextGenerateRequest.TextMessageItem();
        user.setRole("user");
        user.setParts(List.of(text, picture));

        MediaTextGenerateRequest request = new MediaTextGenerateRequest();
        request.setUserId(userId);
        request.setModelName(model);
        request.setStrictModelSelection(true);
        request.setBizTaskType(TASK_TYPE);
        request.setBizTaskId(skill.getId());
        request.setCallId(callId);
        request.setStream(false);
        request.setRequireNativeNonStream(true);
        request.setReasoningEnabled(false);
        request.setMessages(List.of(user));
        request.setOptions(Map.of(StructuredOutputSupport.ENABLED_KEY, true,
                TextReasoningOptionsResolver.MAX_OUTPUT_TOKENS_KEY, 2048));
        return toStatus(mediaGenerationService.submitText(request));
    }

    public ActiveAnalysis active(Long userId) {
        AidMediaTask task = findActiveTask(userId);
        return task == null ? null : new ActiveAnalysis(task.getId().toString(), referenceImage(task));
    }

    private AidMediaTask findActiveTask(Long userId) {
        return taskMapper.selectOne(Wrappers.<AidMediaTask>lambdaQuery()
                .select(AidMediaTask::getId, AidMediaTask::getRequestJson)
                .eq(AidMediaTask::getUserId, userId)
                .eq(AidMediaTask::getBizTaskType, TASK_TYPE)
                .notIn(AidMediaTask::getStatus, MediaTaskStatus.SUCCEEDED.name(),
                        MediaTaskStatus.FAILED.name(), MediaTaskStatus.CANCELLED.name())
                .orderByDesc(AidMediaTask::getId)
                .last("LIMIT 1"));
    }

    private String referenceImage(AidMediaTask task) {
        try {
            var request = JSON.parseObject(task.getRequestJson(), MediaTextGenerateRequest.class);
            return request.getMessages().stream()
                    .filter(message -> message != null && message.getParts() != null)
                    .flatMap(message -> message.getParts().stream())
                    .filter(part -> part != null && "image".equals(part.getType()))
                    .map(MediaTextGenerateRequest.TextContentPart::getUrl)
                    .filter(url -> url != null && metadata.isConfiguredStorageUrl(url))
                    .findFirst().orElse(null);
        } catch (RuntimeException invalidSnapshot) {
            return null;
        }
    }

    public AnalysisStatus status(Long taskId, Long userId) {
        AidMediaTask task = taskMapper.selectOne(Wrappers.<AidMediaTask>lambdaQuery()
                .select(AidMediaTask::getId)
                .eq(AidMediaTask::getId, taskId)
                .eq(AidMediaTask::getUserId, userId)
                .eq(AidMediaTask::getBizTaskType, TASK_TYPE));
        if (task == null) {
            throw new ServiceException("风格解析任务不存在");
        }
        return toStatus(mediaGenerationService.queryTaskLocal(taskId));
    }

    private AnalysisStatus toStatus(MediaTaskResponse task) {
        if (task == null) {
            throw new ServiceException("风格解析任务不存在");
        }
        String id = task.getTaskId().toString();
        return switch (task.getStatus()) {
            case "SUCCEEDED" -> completed(id, task.getTextContent());
            case "FAILED", "CANCELLED" -> new AnalysisStatus(id, "FAILED", null,
                    task.getUserMessage() == null ? "风格解析失败，可重试或手动填写" : task.getUserMessage());
            default -> new AnalysisStatus(id, "PENDING", null, null);
        };
    }

    private AnalysisStatus completed(String taskId, String text) {
        try {
            if (text == null || text.length() > 32_000) {
                throw new IllegalArgumentException("无效的模型输出");
            }
            String body = text.strip();
            if (body.matches("(?is)^" + (char) 96 + "{3}json\\s+.*\\s+" + (char) 96 + "{3}$")) {
                body = body.substring(body.indexOf('\n') + 1, body.length() - 3).strip();
            }
            JSONObject fields = JSON.parseObject(body);
            if (fields == null || !fields.keySet().equals(FIELD_LIMITS.keySet())) {
                throw new IllegalArgumentException("风格字段不完整");
            }
            FIELD_LIMITS.forEach((name, limit) -> {
                if (!(fields.get(name) instanceof String value) || value.isBlank() || value.strip().length() > limit) {
                    throw new IllegalArgumentException("风格字段无效");
                }
                fields.put(name, value.strip());
            });
            return new AnalysisStatus(taskId, "SUCCEEDED", new StyleResult(fields.getString("assetName"),
                    fields.getString("personalityDesc"), fields.getString("promptText"), fields.getString("remark")), null);
        } catch (RuntimeException invalid) {
            return new AnalysisStatus(taskId, "FAILED", null, "模型未返回有效的风格资料，可重试或手动填写");
        }
    }

    public record StyleResult(String assetName, String personalityDesc, String promptText, String remark) {}
    public record AnalysisStatus(String taskId, String status, StyleResult result, String message) {}
    public record ActiveAnalysis(String taskId, String imageUrl) {}
}
