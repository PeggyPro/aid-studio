package com.aid.runtime.tool;

import com.aid.aid.domain.model.ModelParameter;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.*;

/** Uses the already resolved model parameter definition; does not query catalogs or guess provider keys. */
public final class RuntimeModelParameterContract {
    private RuntimeModelParameterContract() { }

    public static Map<String,Object> project(String toolSchema, List<ModelParameter> parameters,
                                             Map<String,String> canonicalToRequestField) {
        return project(toolSchema,parameters,canonicalToRequestField,Set.of());
    }

    public static Map<String,Object> project(String toolSchema, List<ModelParameter> parameters,
                                             Map<String,String> canonicalToRequestField,Set<String> hostFields) {
        JSONObject source = JSON.parseObject(toolSchema);
        JSONObject properties = source.getJSONObject("properties");
        if (properties == null) throw new IllegalArgumentException("Tool properties missing");
        Map<String,ModelParameter> fields = new LinkedHashMap<>();
        collect(parameters,"",fields,0);
        Set<String> supported = new LinkedHashSet<>();
        supported.add("modelCode"); supported.add("capabilityCode");
        if(!properties.keySet().containsAll(hostFields))throw new IllegalArgumentException("Unknown host-owned Tool fields");
        supported.addAll(hostFields);
        Map<String,Object> constraints = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>();
        for (String name : properties.keySet()) {
            ModelParameter parameter = fields.get(name);
            if(parameter==null && canonicalToRequestField.containsKey(name))parameter=fields.get(canonicalToRequestField.get(name));
            if (parameter == null) continue;
            supported.add(name);
            Map<String,Object> field = new LinkedHashMap<>();
            if (parameter.getChoices() != null && !parameter.getChoices().isEmpty())
                field.put("enum", List.copyOf(parameter.getChoices()));
            String lower = "string".equals(parameter.getType()) ? "minLength"
                    : "array".equals(parameter.getType()) ? "minItems" : "minimum";
            String upper = "string".equals(parameter.getType()) ? "maxLength"
                    : "array".equals(parameter.getType()) ? "maxItems" : "maximum";
            if (parameter.getMinimum() != null) field.put(lower, parameter.getMinimum());
            if (parameter.getMaximum() != null) field.put(upper, parameter.getMaximum());
            if (Boolean.TRUE.equals(parameter.getRequired()) && parameter.getDefaultValue() == null)
                required.add(name);
            if (!field.isEmpty()) constraints.put(name, field);
        }
        // Required canonical content is the Tool's business input; source/resource fields are server-bound.
        if (source.getJSONArray("required") != null)
            for (Object field : source.getJSONArray("required")) supported.add((String)field);
        return Map.of("supportedFields", List.copyOf(supported), "fieldConstraints", constraints,
                "requiredFields", List.copyOf(required),
                "unsupportedFieldPolicy", "只能填写本次能力声明的字段；未提供的可选值由已验证的模型配置处理，不得猜测供应商参数。");
    }

    private static void collect(List<ModelParameter> parameters,String prefix,Map<String,ModelParameter> fields,int depth) {
        if(parameters==null)return;
        if(depth>16||fields.size()>512)throw new IllegalArgumentException("Model parameter contract exceeds limits");
        for(ModelParameter field:parameters) {
            if(field==null||field.getName()==null)continue;
            String name=prefix+field.getName();
            if(fields.putIfAbsent(name,field)!=null)throw new IllegalArgumentException("Duplicate model parameter path");
            collect(field.getProperties(),name+".",fields,depth+1);
        }
    }
}
