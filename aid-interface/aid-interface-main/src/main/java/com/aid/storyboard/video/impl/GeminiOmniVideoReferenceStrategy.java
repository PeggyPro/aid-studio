package com.aid.storyboard.video.impl;

import cn.hutool.core.util.StrUtil;
import com.aid.common.exception.ServiceException;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.provider.GeminiOmniInteractionsCodec;
import com.aid.storyboard.video.AbstractVideoReferenceStrategy;
import com.aid.storyboard.video.ResolvedReference;
import com.aid.storyboard.video.VideoReferenceContext;
import com.aid.storyboard.video.VideoReferencePlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Interactions 的分镜输入装配；素材读取和编码仍由统一媒体执行链处理。 */
@Component
public class GeminiOmniVideoReferenceStrategy extends AbstractVideoReferenceStrategy {
    @Override public boolean supportsModelConfig(AiModelConfigVo model) {
        return model != null && GeminiOmniInteractionsCodec.PROTOCOL.equals(model.getProtocol());
    }

    @Override public VideoReferencePlan assemble(VideoReferenceContext context) {
        String mode = StrUtil.blankToDefault(context.getModelConfig().getCapabilityCode(),
                context.getModelConfig().getGenerateMode());
        if ("reference_to_video".equals(mode)) return references(context);
        if ("text_to_video".equals(mode)) {
            if (StrUtil.isNotBlank(context.getBaseImageUrl()) || !context.getReferences().isEmpty())
                throw new ServiceException("文生视频不接受参考图片，请选择参考素材生视频");
            return VideoReferencePlan.of(composePrompt(context.getVideoPrompt(), null,
                    context.getUserInputText()), List.of(), null);
        }
        String first = pickSingleFrame(context);
        if (StrUtil.isBlank(first)) throw new ServiceException("请先选择首帧图片");
        ResolvedReference reference = pickSingleReference(context);
        List<ResolvedReference> selected = reference == null ? List.of() : List.of(reference);
        String prompt = composePrompt(remapPromptForPicked(context.getVideoPrompt(), selected),
                null, context.getUserInputText());
        if ("image_to_video".equals(mode)) return VideoReferencePlan.of(prompt, List.of(first), first);
        if ("start_end_to_video".equals(mode) && StrUtil.isNotBlank(context.getLastFrameImageUrl()))
            return VideoReferencePlan.of(prompt, List.of(first, context.getLastFrameImageUrl()), first,
                    Map.of("lastFrameImageUrl", context.getLastFrameImageUrl()));
        throw new ServiceException("此分镜入口不支持所选视频能力或缺少尾帧");
    }

    private VideoReferencePlan references(VideoReferenceContext context) {
        List<String> urls = new ArrayList<>();
        boolean base = StrUtil.isNotBlank(context.getBaseImageUrl());
        if (base) urls.add(context.getBaseImageUrl());
        List<ResolvedReference> selected = new ArrayList<>();
        for (ResolvedReference reference : context.getReferences()) {
            if (reference != null && StrUtil.isNotBlank(reference.getUrl()) && !urls.contains(reference.getUrl())) {
                selected.add(reference);
                urls.add(reference.getUrl());
            }
        }
        // Video references are assembled separately; the codec validates the combined media count.
        if (urls.size() > context.getMaxReferenceImages())
            throw new ServiceException("参考图片数量不符合所选能力限制");
        int offset = base ? 1 : 0;
        String legend = buildReferenceLegend(selected, offset);
        if (base) legend = REFERENCE_LEGEND_PREFIX + "图片1=基础参考"
                + (StrUtil.isBlank(legend) ? "" : "，" + legend.substring(REFERENCE_LEGEND_PREFIX.length()));
        String prompt = composePrompt(remapPromptForPicked(context.getVideoPrompt(), selected, offset),
                legend, context.getUserInputText());
        return VideoReferencePlan.of(prompt, urls, null, Map.of("referenceImages", urls));
    }
}
