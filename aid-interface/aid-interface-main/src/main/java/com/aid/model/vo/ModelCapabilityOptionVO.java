package com.aid.model.vo;

import com.aid.aid.domain.model.ModelParameter;
import com.aid.aid.domain.model.ModelParameterRule;
import com.aid.billing.vo.ModelBillingDetailVO;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** C 端可选择的模型能力视图，不暴露供应商路由、固定入参和成本配置。 */
@Data
public class ModelCapabilityOptionVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 当前音频能力的官方输入表单与素材约束。 */
    private java.util.Map<String, Object> audio;

    /** Server-only execution support; never a public model parameter. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @com.alibaba.fastjson2.annotation.JSONField(serialize = false, deserialize = false)
    private Boolean runtimeMusicSupported;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @com.alibaba.fastjson2.annotation.JSONField(serialize = false, deserialize = false)
    private java.util.Map<String,Object> runtimeMusicInputSupport;

    private String code;
    private String label;
    private String generateMode;
    private List<ModelParameter> parameterSchema = new ArrayList<>();
    private List<ModelParameterRule> parameterRules = new ArrayList<>();
    private Map<String, Object> presentation;
    private ModelBillingDetailVO billing;
}
