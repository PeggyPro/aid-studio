package com.aid.businessscene;

import com.aid.billing.dto.BillingQuoteRequest;
import com.aid.billing.service.BillingQuoteService;
import com.aid.billing.vo.BillingQuoteVO;
import com.aid.compute.ComputeWorkerClient;
import com.aid.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.util.*;

/** Read-only scene authorization around the existing price calculator; never freezes funds or submits work. */
@Service
public class BusinessSceneQuoteService {
    private final BusinessEntrypointService scenes;private final BillingQuoteService billing;
    public BusinessSceneQuoteService(BusinessEntrypointService scenes,BillingQuoteService billing){this.scenes=scenes;this.billing=billing;}
    public record Request(String entryKind,String entryCode,String entryVersionId,String modelCode,JsonNode input) { }
    public record Quote(String sceneCode,String bindingRevision,String priceBasisDigest,BusinessEntrypointService.Binding entrypoint,BillingQuoteVO modelQuote) { }
    public Quote quote(String scene,Request request,Long userId){
        if(userId==null||request==null||request.modelCode()==null||request.input()==null||!request.input().isObject())throw new ServiceException("场景报价参数无效",400);
        var catalog=scenes.read(scene);
        var entry=catalog.bindings().stream().filter(item->item.available()&&item.binding().kind().equals(request.entryKind())
            &&item.binding().code().equals(request.entryCode())&&item.binding().versionId().equals(request.entryVersionId())).findFirst()
            .orElseThrow(()->new ServiceException("所选入口不属于当前业务固定绑定",409));
        if(!entry.allowedModelCodes().contains(request.modelCode()))throw new ServiceException("所选模型不属于当前业务入口",409);
        var model=catalog.models().stream().filter(item->request.modelCode().equals(item.getModelCode())).findFirst().orElseThrow(()->new ServiceException("业务模型当前不可用",409));
        if(model.getBilling()==null||!Boolean.TRUE.equals(model.getIsFree())&&(model.getBilling().getRules()==null||model.getBilling().getRules().isEmpty()))throw new ServiceException("缺少权威计价依据，暂不能报价",409);
        String type=switch(model.getModelType()){case "text"->"MEDIA_TEXT";case "image"->"MEDIA_IMAGE";case "video"->"MEDIA_VIDEO";default->throw new ServiceException("此业务报价入口不支持当前模型类型",400);};
        ObjectNode payload=request.input().deepCopy();
        if(payload.hasNonNull("modelName")&&!request.modelCode().equals(payload.get("modelName").asText()))throw new ServiceException("报价模型与入口选择不一致",409);
        payload.put("modelName",request.modelCode());if(model.getCapabilityCode()!=null)payload.put("capabilityCode",model.getCapabilityCode());
        var input=new BillingQuoteRequest();input.setQuoteType(type);input.setQuantity(1);input.setPayload(payload);
        var quote=billing.quote(input,userId);
        if(!Boolean.TRUE.equals(quote.getMatched())||!request.modelCode().equals(quote.getModelCode()))throw new ServiceException("模型报价与业务选择不一致",409);
        // Bind evidence to the result actually calculated, not a catalog read that can precede a price update.
        return new Quote(scene,catalog.revision(),ComputeWorkerClient.digest(quote),entry.binding(),quote);
    }
}
