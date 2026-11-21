package com.aid.aid.domain.model;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

/** 模型参数的类型、取值范围和表单展示定义。 */
@Data
public class ModelParameter {
    private String name;
    private String label;
    private String description;
    private String type;
    private String widget;
    private String unit;
    private Boolean required;
    private Object defaultValue;
    private BigDecimal minimum;
    private BigDecimal maximum;
    private BigDecimal step;
    private List<Object> choices;
    private List<ModelParameter> properties;
    private ModelParameter items;
    /** 官方联合类型；每个分支使用独立参数作用域。 */
    private List<ModelParameter> alternatives;
    private String materialRole;
    private List<String> formats;
    private BigDecimal minDurationSeconds;
    private BigDecimal maxDurationSeconds;
    private BigDecimal maxTotalDurationSeconds;
    private BigDecimal maxFileSizeMb;
    private Long maxFileSizeBytes;
    private BigDecimal clipDurationSeconds;
}
