package com.aid.billing.service.impl;

import com.aid.billing.dto.BillingBudgetViews.TextQuoteView;
import com.aid.billing.dto.BillingBudgetViews.QuoteView;
import com.aid.billing.dto.BillingBudgetViews.ImageQuoteView;
import com.aid.billing.dto.BillingBudgetViews.VideoQuoteView;
import com.aid.billing.dto.BillingBudgetViews.AudioQuoteView;
import com.aid.billing.dto.BillingCalcResult;
import com.aid.billing.dto.BillingInput;
import com.aid.billing.service.AssistantMediaBillingQuoteService;
import com.aid.billing.service.BillingPreHoldCalculationService;
import com.aid.billing.util.AssistantMediaQuoteDigest;
import com.aid.domain.vo.AiModelConfigVo;
import com.aid.media.dto.MediaTextGenerateRequest;
import com.aid.media.dto.MediaImageGenerateRequest;
import com.aid.media.dto.MediaVideoGenerateRequest;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.media.dto.PreparedMediaBillingInput;
import com.aid.media.service.MediaBillingQuotePreparer;
import com.aid.media.service.MediaPreparationErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

/** 与正式prepareBilling复用同一模型归一化和预冻结计算链。 */
@Service
@RequiredArgsConstructor
public class AssistantMediaBillingQuoteServiceImpl implements AssistantMediaBillingQuoteService {

    private static final long QUOTE_TTL_MILLIS = 5 * 60 * 1000L;
    private final MediaBillingQuotePreparer quotePreparer;
    private final BillingPreHoldCalculationService calculationService;

    @Override
    public TextQuoteView quoteText(MediaTextGenerateRequest request) {
        if (request == null || request.getBillingBudgetContext() != null) {
            throw MediaPreparationErrorCode.QUOTE_SNAPSHOT_INVALID.reject("报价请求无效");
        }
        PreparedMediaBillingInput prepared = quotePreparer.prepareTrustedTextBilling(request);
        AiModelConfigVo modelConfig = prepared.modelConfig();
        BillingInput billingInput = prepared.billingInput();
        String requestDigest = AssistantMediaQuoteDigest.requestDigest(request);
        var identity = AssistantMediaQuoteDigest.newQuoteIdentity(QUOTE_TTL_MILLIS);
        String quoteId = identity.quoteId();
        Date expiresAt = identity.expiresAt();
        BillingCalcResult calculation = calculationService.calculate(modelConfig, billingInput);

        TextQuoteView view = new TextQuoteView();
        view.setNormalizedRequest(request);
        view.setModelCode(modelConfig.getModelCode());
        view.setModelConfigVersion(modelConfig.getConfigVersion());
        view.setBillingVersion(modelConfig.getBillingVersion());
        view.setQuoteId(quoteId);
        view.setRequestDigest(requestDigest);
        view.setExpiresAt(expiresAt);
        if (calculation == null || !calculation.isMatched() || calculation.getSnapshot() == null
                || calculation.getAmount() == null || billingInput.isInputMetadataPending()) {
            view.setHardUpperBoundAvailable(false);
            view.setUnavailableReason(calculation == null ? "计费规则缺失" : calculation.getErrorMessage());
            return view;
        }

        view.setMeterType(calculation.getSnapshot().getMeterType());
        view.setSkuCode(calculation.getSkuCode());
        view.setEstimatedAmount(calculation.getAmount());
        view.setQuotedUpperBound(AssistantMediaQuoteDigest.quotedUpperBound(calculation.getAmount()));
        view.setQuoteDigest(AssistantMediaQuoteDigest.quoteDigest(
                quoteId, requestDigest, expiresAt, modelConfig, billingInput, calculation));
        view.setHardUpperBoundAvailable(true);
        return view;
    }

    @Override
    public ImageQuoteView quoteImage(MediaImageGenerateRequest request) {
        requireUnquoted(request == null ? null : request.getBillingBudgetContext());
        PreparedMediaBillingInput prepared = quotePreparer.prepareTrustedImageBilling(request);
        QuoteProjection quote = quote(request, prepared);
        ImageQuoteView view = new ImageQuoteView();
        copy(quote, view);
        view.setNormalizedRequest(request);
        return view;
    }

    @Override
    public VideoQuoteView quoteVideo(MediaVideoGenerateRequest request) {
        requireUnquoted(request == null ? null : request.getBillingBudgetContext());
        PreparedMediaBillingInput prepared = quotePreparer.prepareTrustedVideoBilling(request);
        QuoteProjection quote = quote(request, prepared);
        VideoQuoteView view = new VideoQuoteView();
        copy(quote, view);
        view.setNormalizedRequest(request);
        return view;
    }

    @Override
    public AudioQuoteView quoteAudio(MediaAudioGenerateRequest request) {
        requireUnquoted(request == null ? null : request.getBillingBudgetContext());
        PreparedMediaBillingInput prepared = quotePreparer.prepareTrustedAudioBilling(request);
        QuoteProjection quote = quote(request, prepared);
        AudioQuoteView view = new AudioQuoteView();
        copy(quote, view);
        view.setNormalizedRequest(request);
        return view;
    }

    private QuoteProjection quote(Object request, PreparedMediaBillingInput prepared) {
        AiModelConfigVo modelConfig = prepared.modelConfig();
        BillingInput billingInput = prepared.billingInput();
        String requestDigest = AssistantMediaQuoteDigest.requestDigest(request);
        var identity = AssistantMediaQuoteDigest.newQuoteIdentity(QUOTE_TTL_MILLIS);
        String quoteId = identity.quoteId();
        Date expiresAt = identity.expiresAt();
        BillingCalcResult calculation = calculationService.calculate(modelConfig, billingInput);
        QuoteProjection projection = new QuoteProjection();
        projection.modelCode = modelConfig.getModelCode();
        projection.modelConfigVersion = modelConfig.getConfigVersion();
        projection.billingVersion = modelConfig.getBillingVersion();
        projection.quoteId = quoteId;
        projection.requestDigest = requestDigest;
        projection.expiresAt = expiresAt;
        if (calculation == null || !calculation.isMatched() || calculation.getSnapshot() == null
                || calculation.getAmount() == null || billingInput.isInputMetadataPending()) {
            projection.unavailableReason = calculation == null ? "billing rule is unavailable"
                    : calculation.getErrorMessage();
            return projection;
        }
        projection.meterType = calculation.getSnapshot().getMeterType();
        projection.skuCode = calculation.getSkuCode();
        projection.estimatedAmount = calculation.getAmount();
        projection.quotedUpperBound = AssistantMediaQuoteDigest.quotedUpperBound(calculation.getAmount());
        projection.quoteDigest = AssistantMediaQuoteDigest.quoteDigest(
                quoteId, requestDigest, expiresAt, modelConfig, billingInput, calculation);
        projection.hardUpperBoundAvailable = true;
        return projection;
    }

    private void requireUnquoted(Object budgetContext) {
        if (budgetContext != null) {
            throw MediaPreparationErrorCode.QUOTE_SNAPSHOT_INVALID.reject(
                    "quote request already contains a budget context");
        }
    }

    private void copy(QuoteProjection source, QuoteView target) {
        target.setModelCode(source.modelCode);
        target.setModelConfigVersion(source.modelConfigVersion);
        target.setBillingVersion(source.billingVersion);
        target.setMeterType(source.meterType);
        target.setSkuCode(source.skuCode);
        target.setQuoteId(source.quoteId);
        target.setRequestDigest(source.requestDigest);
        target.setQuoteDigest(source.quoteDigest);
        target.setEstimatedAmount(source.estimatedAmount);
        target.setQuotedUpperBound(source.quotedUpperBound);
        target.setExpiresAt(source.expiresAt);
        target.setHardUpperBoundAvailable(source.hardUpperBoundAvailable);
        target.setUnavailableReason(source.unavailableReason);
    }

    private static class QuoteProjection {
        private String modelCode;
        private Long modelConfigVersion;
        private Integer billingVersion;
        private String meterType;
        private String skuCode;
        private String quoteId;
        private String requestDigest;
        private String quoteDigest;
        private java.math.BigDecimal estimatedAmount;
        private java.math.BigDecimal quotedUpperBound;
        private Date expiresAt;
        private boolean hardUpperBoundAvailable;
        private String unavailableReason;
    }
    /** Only a digest-bound, originally hard-quoted server preparation may call this path. */
    @Override
    public VideoQuoteView quoteFrozenReadOnlyVideo(MediaVideoGenerateRequest request, String approvedRequestDigest) {
        return quoteFrozenReadOnlyVideo(request, approvedRequestDigest, java.util.Map.of());
    }

    @Override
    public VideoQuoteView quoteFrozenReadOnlyVideo(MediaVideoGenerateRequest request, String approvedRequestDigest,
            java.util.Map<String,com.aid.media.dto.AuthorizedImageAssetSnapshot> authorizedImages) {
        requireUnquoted(request == null ? null : request.getBillingBudgetContext());
        if (request == null || approvedRequestDigest == null
                || !approvedRequestDigest.equals(AssistantMediaQuoteDigest.requestDigest(request)))
            throw new com.aid.common.exception.ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        boolean verifiedImages = com.aid.billing.util.FrozenVideoQuoteImages.requireMatching(request, authorizedImages);
        // An unchanged URL alone is not evidence; images require current authorized snapshots.
        if (!verifiedImages && request.getImageUrl()!=null && !request.getImageUrl().isBlank()
                || request.getReferenceAudios()!=null && !request.getReferenceAudios().isEmpty()
                || quoteHasBackgroundImageResource(request))
            throw new com.aid.common.exception.ServiceException("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
        var oldRefs = request.getResolvedReferenceVideos()==null ? java.util.List.<com.aid.media.dto.ReferenceVideoInput>of()
                : java.util.List.copyOf(request.getResolvedReferenceVideos());
        var sourceIds = quoteSourceVideoIds(request)==null ? java.util.List.<Long>of() : java.util.List.copyOf(quoteSourceVideoIds(request));
        if (sourceIds.size()!=oldRefs.size() || sourceIds.size()!=new java.util.HashSet<>(sourceIds).size())
            throw new com.aid.common.exception.ServiceException("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
        for (int index=0;index<sourceIds.size();index++)
            requireCompleteFrozenVideoReference(sourceIds.get(index),oldRefs.get(index));
        var remainingOptions = verifiedImages
                ? com.aid.billing.util.FrozenVideoQuoteImages.withoutImageOptions(request.getOptions()) : request.getOptions();
        if (hasUnregisteredQuoteReference(remainingOptions,!sourceIds.isEmpty()))
            throw new com.aid.common.exception.ServiceException("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
        // This exact existing resolver path overwrites all internal references from owned current
        // registered resource/record rows with allowProbe=false; no metadata prepare/CAS/probe.
        PreparedMediaBillingInput prepared = quotePreparer.prepareReadOnlyVideoBilling(request);
        var current = request.getResolvedReferenceVideos()==null ? java.util.List.<com.aid.media.dto.ReferenceVideoInput>of()
                : request.getResolvedReferenceVideos();
        if (!oldRefs.equals(current))
            throw new com.aid.common.exception.ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        for (int index=0;index<sourceIds.size();index++) requireCompleteFrozenVideoReference(sourceIds.get(index),current.get(index));
        if (!sourceIds.isEmpty()) {
            // Match the existing trusted validator's metadata-derived fields from the freshly
            // re-read exact owned records, not any caller duration or a fixed trusted boolean.
            var options=new java.util.LinkedHashMap<String,Object>(request.getOptions()==null?java.util.Map.of():request.getOptions());
            var durations=current.stream().map(value->java.math.BigDecimal.valueOf(value.getDurationMs())
                    .divide(java.math.BigDecimal.valueOf(1000))).toList();
            var total=durations.stream().reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
            options.put("referenceVideoDurations",durations);options.put("referenceVideoSeconds",total);options.put("inputVideoSeconds",total);
            request.setOptions(options);
            prepared=new PreparedMediaBillingInput(prepared.modelConfig(),com.aid.billing.util.BillingInputExtractor.fromVideoRequest(request,prepared.modelConfig()));
        }
        if (verifiedImages) com.aid.billing.util.FrozenVideoQuoteImages.requireMatching(request, authorizedImages);
        if (!approvedRequestDigest.equals(AssistantMediaQuoteDigest.requestDigest(request)))
            throw new com.aid.common.exception.ServiceException("OPERATION_QUOTE_INPUT_CHANGED");
        // The generic readonly API conservatively labels every video pending. This narrow path
        // has now independently re-read full persisted metadata for the original server IDs,
        // proved every field equal and proved there is no unregistered/raw reference fallback.
        prepared.billingInput().setInputMetadataPending(false);
        QuoteProjection quote = quote(request, prepared);
        VideoQuoteView view = new VideoQuoteView();copy(quote,view);view.setNormalizedRequest(request);return view;
    }

    private void requireCompleteFrozenVideoReference(Long id, com.aid.media.dto.ReferenceVideoInput value) {
        if (id==null || id<=0 || value==null || !id.equals(value.getRecordId())
                || value.getVideoUrl()==null || value.getVideoUrl().isBlank()
                || value.getDurationMs()==null || value.getDurationMs()<=0
                || value.getFileSizeBytes()==null || value.getFileSizeBytes()<=0
                || value.getWidth()==null || value.getWidth()<=0 || value.getHeight()==null || value.getHeight()<=0
                || value.getFps()==null || value.getFps().signum()<=0 || value.getFormat()==null || value.getFormat().isBlank())
            throw new com.aid.common.exception.ServiceException("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
    }

    private boolean hasUnregisteredQuoteReference(Object value,boolean ownedVideoReferences) {
        if (value instanceof java.util.Map<?,?> map) {
            for (var entry:map.entrySet()) {
                if (ownedVideoReferences && java.util.Set.of("referenceVideos","referenceVideoDurations","referenceVideoSeconds","inputVideoSeconds").contains(entry.getKey()))continue;
                String key=String.valueOf(entry.getKey()).toLowerCase(java.util.Locale.ROOT);
                if (key.contains("url") || key.contains("reference")
                        || java.util.Set.of("images","videos","audios","firstframe","lastframe","backgroundimage","inputimage","inputvideo","inputaudio").contains(key)) return true;
                if (hasUnregisteredQuoteReference(entry.getValue(),false)) return true;
            }
        } else if (value instanceof Iterable<?> items) {
            for (var item:items) if (hasUnregisteredQuoteReference(item,false)) return true;
        } else if(value instanceof String text && (text.startsWith("https://") || text.startsWith("http://") || text.startsWith("/profile/")))return true;
        return false;
    }

    private java.util.List<Long> quoteSourceVideoIds(MediaVideoGenerateRequest request) {
        return request.getReferenceVideoRecordIds();
    }

    private boolean quoteHasBackgroundImageResource(MediaVideoGenerateRequest request) {
        return false;
    }

}
