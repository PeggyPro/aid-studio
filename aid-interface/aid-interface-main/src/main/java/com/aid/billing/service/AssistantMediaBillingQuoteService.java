package com.aid.billing.service;

import com.aid.billing.dto.BillingBudgetViews.TextQuoteView;
import com.aid.billing.dto.BillingBudgetViews.ImageQuoteView;
import com.aid.billing.dto.BillingBudgetViews.VideoQuoteView;
import com.aid.billing.dto.BillingBudgetViews.AudioQuoteView;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;

/** 不建媒体任务、不冻结钱包、不调用Provider的服务端可信报价。 */
public interface AssistantMediaBillingQuoteService {

    TextQuoteView quoteText(MediaTextGenerateRequest request);

    ImageQuoteView quoteImage(MediaImageGenerateRequest request);

    VideoQuoteView quoteVideo(MediaVideoGenerateRequest request);

    AudioQuoteView quoteAudio(MediaAudioGenerateRequest request);
    /** No metadata probe; accepts only exact previously trusted registered video input. */
    VideoQuoteView quoteFrozenReadOnlyVideo(MediaVideoGenerateRequest request, String approvedRequestDigest);
    /** Reprice exact image references after current resource authorization and metadata validation. */
    VideoQuoteView quoteFrozenReadOnlyVideo(MediaVideoGenerateRequest request, String approvedRequestDigest,
            java.util.Map<String,com.aid.media.dto.AuthorizedImageAssetSnapshot> authorizedImages);
}
