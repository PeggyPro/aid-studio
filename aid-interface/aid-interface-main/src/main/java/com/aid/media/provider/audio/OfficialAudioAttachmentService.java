package com.aid.media.provider.audio;

import com.aid.aid.domain.AidAiModel;
import com.aid.common.exception.ServiceException;
import com.aid.media.dto.MediaAudioGenerateRequest;
import com.aid.model.definition.ModelBusinessBindingService;
import com.aid.model.definition.ModelInvocationResolver;
import com.aid.service.IAiModelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 参考文件登记复用官方传输与资源归属，不作为生成任务或计费调用。 */
@Service
@RequiredArgsConstructor
public class OfficialAudioAttachmentService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final IAiModelConfigService configs;
    private final ModelBusinessBindingService bindings;
    private final ModelInvocationResolver invocations;
    private final OfficialAudioClientContract clientContracts;
    private final OfficialAudioContractRegistry contracts;
    private final OfficialAudioInputService inputs;
    private final OfficialAudioTransport transport;
    private final OfficialAudioResponseCodec codec;
    private final OfficialAudioResourceService resources;
    private final RedissonClient redis;

    public String prepare(String modelCode, String capabilityCode, String businessCode,
                          String field, String trustedUrl, Long userId) {
        var config = configs.selectByModelCodeForUser(modelCode, userId, capabilityCode);
        if (config == null) throw new ServiceException("模型不可用");
        String selected = bindings.capability(config.getId(), businessCode, capabilityCode);
        config = invocations.select(config, selected);
        var model = new AidAiModel();
        model.setModelType("audio"); model.setRealModelCode(config.getRealModelCode()); model.setCapabilityJson(config.getCapabilityJson());
        var display = clientContracts.describe(model);
        Object raw = display == null ? null : ((Map<?, ?>) display.get("files")).get(field);
        if (!(raw instanceof Map<?, ?> file) || !Boolean.TRUE.equals(file.get("prepareUpload"))) throw new ServiceException("附件用途不支持");
        String purpose = String.valueOf(file.get("purpose"));
        String key = "aid:official-audio:upload:" + cn.hutool.crypto.SecureUtil.sha256(
                config.getProviderId() + ":" + userId + ":" + purpose + ":" + trustedUrl);
        var lock = redis.getLock(key + ":lock");
        boolean locked = false;
        try {
            locked = lock.tryLock(10, TimeUnit.SECONDS);
            if (!locked) throw new ServiceException("附件正在处理");
            var cached = redis.<String>getBucket(key);
            String existing = cached.get();
            if (existing != null) return existing;
            var contract = contracts.contract("mureka.post.files_upload");
            var plan = new OfficialAudioContractRegistry.Plan("mureka.post.files_upload", "mureka", "POST",
                    contract.path("contentType").asText(), "json", false,
                    Map.of("file", trustedUrl, "purpose", purpose), Map.of(), Map.of());
            var request = new MediaAudioGenerateRequest(); request.setUserId(userId);
            inputs.prepare(config, request, plan, true);
            config.setApiSuffix(contract.path("path").asText());
            var result = codec.submit(transport.submit(config, plan, contract), plan);
            if (result.getConfirmedFailureCode() != null) throw new ServiceException(
                    result.getSubmissionErrorMessage() == null ? "附件登记失败" : result.getSubmissionErrorMessage());
            if (result.getDirectText() == null) throw new ServiceException("附件登记结果未知，请稍后重新查询");
            String id = JSON.readTree(result.getDirectText()).path("id").asText("");
            if (id.isBlank()) throw new ServiceException("附件回执缺失");
            resources.record(config, request, plan, result);
            cached.set(id, 10, TimeUnit.MINUTES);
            return id;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new ServiceException("附件处理已中断");
        } catch (ServiceException rejected) { throw rejected; }
        catch (Exception invalid) { throw new ServiceException("附件登记失败"); }
        finally { if (locked && lock.isHeldByCurrentThread()) lock.unlock(); }
    }
}
