package com.aid.assistant.service;

import com.aid.assistant.effect.VerifiedRuntimeToolEffect;
import com.alibaba.fastjson2.JSONObject;

import java.util.Set;

/** Java-owned projection of a durable business receipt, without claiming a locatable target. */
public interface AssistantToolReceiptProjector {
    Set<String> supportedToolCodes();
    JSONObject projectCurrentReceipt(VerifiedRuntimeToolEffect effect);
}
