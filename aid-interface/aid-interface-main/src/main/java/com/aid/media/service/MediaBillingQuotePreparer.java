package com.aid.media.service;

import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.PreparedMediaBillingInput;

/** 复用正式生成前置校验的媒体计费输入准备器；不创建任务、不调用上游。 */
public interface MediaBillingQuotePreparer
{
    PreparedMediaBillingInput prepareImageBilling(MediaImageGenerateRequest request);

    /** Server-trusted, side-effect-free quote preparation using the same validation as submission. */
    PreparedMediaBillingInput prepareTrustedImageBilling(MediaImageGenerateRequest request);

    /** 提示词将由前一只读计划产出的图片报价；仅校验/归一已知规格与输入，不伪造未知 prompt。 */
    PreparedMediaBillingInput preparePlannedImageBilling(MediaImageGenerateRequest request);

    PreparedMediaBillingInput prepareVideoBilling(MediaVideoGenerateRequest request);

    /**
     * Canvas preview quote preparation that resolves only already registered resources and never
     * probes, downloads or calls an upstream provider.
     */
    PreparedMediaBillingInput prepareReadOnlyVideoBilling(MediaVideoGenerateRequest request);

    /** Server-trusted, side-effect-free quote preparation using the same validation as submission. */
    PreparedMediaBillingInput prepareTrustedVideoBilling(MediaVideoGenerateRequest request);

    /** 提示词将由前一只读计划产出的视频报价；仅校验/归一已知规格与输入，不伪造未知 prompt。 */
    PreparedMediaBillingInput preparePlannedVideoBilling(MediaVideoGenerateRequest request);

    PreparedMediaBillingInput prepareTextBilling(MediaTextGenerateRequest request);

    /** 服务端预算编排使用的可信文本报价准备；会完成与正式提交相同的输入核验，但不建任务、不冻结、不调用Provider。 */
    PreparedMediaBillingInput prepareTrustedTextBilling(MediaTextGenerateRequest request);

    PreparedMediaBillingInput prepareAudioBilling(MediaAudioGenerateRequest request);

    /** Server-trusted, side-effect-free quote preparation using the same validation as submission. */
    PreparedMediaBillingInput prepareTrustedAudioBilling(MediaAudioGenerateRequest request);
}
