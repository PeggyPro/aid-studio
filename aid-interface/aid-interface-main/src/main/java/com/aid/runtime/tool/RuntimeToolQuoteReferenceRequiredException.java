package com.aid.runtime.tool;

import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/** Current resource evidence is an explicit revalidation challenge, never execution authority. */
public final class RuntimeToolQuoteReferenceRequiredException extends RuntimeException {
    private final String evidenceJson;
    public RuntimeToolQuoteReferenceRequiredException(JSONObject evidence) {
        super("OPERATION_QUOTE_REFERENCE_REVALIDATION_REQUIRED");
        evidenceJson=JSON.toJSONString(evidence);
    }
    public JSONObject evidence(){return JSON.parseObject(evidenceJson);}
}
