package com.aid.generation.service;

import com.aid.generation.dto.GenerationRequests;
import com.aid.generation.vo.GenerationHistoryResponse;
import com.aid.generation.vo.GenerationResponse;
import com.aid.generation.vo.GenerationSessionPage;
import com.aid.generation.vo.GenerationTaskView;
import com.aid.billing.vo.BillingQuoteVO;

/** 为画布、Skill 和受信任编排提供统一生成入口。 */
public interface IGenericGenerationFacade {
    GenerationResponse generateText(GenerationRequests.Text request, GenerationInvocationContext context);
    GenerationResponse submitText(GenerationRequests.Text request, GenerationInvocationContext context);
    BillingQuoteVO quoteText(GenerationRequests.Text request, GenerationInvocationContext context);
    void generateTextStream(GenerationRequests.Text request, GenerationInvocationContext context,
                            GenerationStreamSink sink);
    GenerationResponse generateImage(GenerationRequests.Image request, GenerationInvocationContext context);
    GenerationResponse submitImage(GenerationRequests.Image request, GenerationInvocationContext context);
    BillingQuoteVO quoteImage(GenerationRequests.Image request, GenerationInvocationContext context);
    GenerationResponse generateVideo(GenerationRequests.Video request, GenerationInvocationContext context);
    GenerationResponse submitVideo(GenerationRequests.Video request, GenerationInvocationContext context);
    BillingQuoteVO quoteVideo(GenerationRequests.Video request, GenerationInvocationContext context);
    GenerationHistoryResponse history(Long sessionId, Long beforeSeq, Integer limit,
                                      GenerationInvocationContext context);
    GenerationSessionPage sessions(Integer pageNum, Integer pageSize, GenerationInvocationContext context);
    GenerationTaskView task(Long taskId, GenerationInvocationContext context);
}
