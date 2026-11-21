package com.aid.model.definition;

import com.aid.aid.domain.model.ModelCapabilityDefinition;
import com.aid.aid.domain.model.ModelParameter;
import com.aid.aid.domain.model.ModelParameterRule;
import com.aid.aid.domain.model.ModelProtocolBinding;
import com.aid.media.service.MediaPreparationErrorCode;
import com.alibaba.fastjson2.JSON;
import java.math.BigDecimal;
import java.util.*;

/** 路由限制只能收窄参数契约；目录、报价和生成共用同一份有效参数。 */
public final class ModelEffectiveParameterConstraints {
    private ModelEffectiveParameterConstraints() { }

    public static ModelCapabilityDefinition resolve(ModelCapabilityDefinition source, ModelProtocolBinding route) {
        ModelCapabilityDefinition result=JSON.parseObject(JSON.toJSONString(source),ModelCapabilityDefinition.class);
        Map<String,Object> capability=route.getCapability()==null?Map.of():route.getCapability();
        Map<String,Object> presentation=new LinkedHashMap<>();
        if(source.getPresentation()!=null)presentation.putAll(source.getPresentation());
        if(route.getPresentation()!=null)presentation.putAll(route.getPresentation());
        Map<String,ModelParameter> fields=new LinkedHashMap<>();collect(result.getParameters(),"",fields);
        List<String> presetSizePaths=List.of("size","options.size","options.resolution").stream()
                .filter(path->!isCustomPixelSize(path,fields.get(path),capability)).toList();
        restrictChoices(result,fields,capability,"sizeOptions","defaultSize",presetSizePaths);
        restrictChoices(result,fields,capability,"aspectRatioOptions","defaultAspectRatio",List.of("aspectRatio","options.aspectRatio","options.aspect_ratio"));
        restrictChoices(result,fields,capability,"durationOptions","defaultDurationSeconds",List.of("durationSeconds"));
        alignDefaults(fields,capability,presentation,"defaultSize","defaultSizeCode",presetSizePaths);
        alignDefaults(fields,capability,presentation,"defaultAspectRatio","defaultAspectRatio",List.of("aspectRatio","options.aspectRatio","options.aspect_ratio"));
        alignDefaults(fields,capability,presentation,"defaultDurationSeconds","defaultDurationSeconds",List.of("durationSeconds"));
        disable(result,fields,capability,presentation,"supportsSizePreset",presetSizePaths);
        disable(result,fields,capability,presentation,"supportsAspectRatio",List.of("aspectRatio","options.aspectRatio","options.aspect_ratio"));
        disable(result,fields,capability,presentation,"supportsDuration",List.of("durationSeconds"));
        for(var entry:fields.entrySet()) {
            String path=entry.getKey();ModelParameter field=entry.getValue();
            if("audio".equals(path)&&Boolean.FALSE.equals(capability.get("supportsAudio"))) {
                field.setChoices(List.of(false));field.setDefaultValue(false);
            }
            String role=field.getMaterialRole();
            String prefix="reference_image".equals(role)?"referenceImage":"reference_video".equals(role)?"referenceVideo":"reference_audio".equals(role)?"referenceAudio":null;
            if(prefix!=null) {
                String plural=prefix.equals("referenceImage")?"Images":prefix.equals("referenceVideo")?"Videos":"Audios";
                if("array".equals(field.getType())) {
                    field.setMinimum(tighterMin(field.getMinimum(),decimal(capability.get("minReference"+plural))));
                    BigDecimal max=decimal(capability.get("maxReference"+plural));
                    if(max!=null&&max.signum()>=0)field.setMaximum(tighterMax(field.getMaximum(),max));
                }
                field.setMinDurationSeconds(tighterMin(field.getMinDurationSeconds(),decimal(capability.get(prefix+"MinDurationSeconds"))));
                field.setMaxDurationSeconds(tighterMax(field.getMaxDurationSeconds(),positive(capability.get(prefix+"MaxDurationSeconds"))));
                field.setMaxTotalDurationSeconds(tighterMax(field.getMaxTotalDurationSeconds(),positive(capability.get(prefix+"MaxTotalDurationSeconds"))));
                field.setMaxFileSizeMb(tighterMax(field.getMaxFileSizeMb(),positive(capability.get(prefix+"MaxFileSizeMb"))));
                if(capability.get(prefix+"Formats") instanceof List<?> formats&&!formats.contains("*")) {
                    List<String> allowed=formats.stream().map(String::valueOf).toList();
                    if(field.getFormats()!=null&&!field.getFormats().isEmpty()&&!field.getFormats().contains("*"))
                        allowed=allowed.stream().filter(value->field.getFormats().stream().anyMatch(value::equalsIgnoreCase)).toList();
                    if(allowed.isEmpty()&&!formats.isEmpty())fail(path+"素材格式约束无交集");
                    field.setFormats(allowed);
                }
            }
            if("expectedImageCount".equals(path)) {
                field.setMaximum(tighterMax(field.getMaximum(),positive(presentation.get("maxOutputCount"))));
                if(field.getMaximum()!=null)presentation.put("maxOutputCount",field.getMaximum().intValueExact());
                if(presentation.get("defaultOutputCount")!=null)field.setDefaultValue(presentation.get("defaultOutputCount"));
                clampDefault(field);
                if(field.getDefaultValue()!=null)presentation.put("defaultOutputCount",field.getDefaultValue());
            }
            String toggle=switch(role==null?"":role){case "first_frame"->"supportsFirstFrame";case "last_frame"->"supportsLastFrame";case "reference_image"->"supportsImageInput";default->null;};
            if(toggle!=null&&(Boolean.FALSE.equals(presentation.get(toggle))||Boolean.FALSE.equals(capability.get(toggle)))) {
                presentation.put(toggle,false);forbid(result,path,field);
            }
            if("reference_video".equals(role)&&Boolean.FALSE.equals(capability.get("supportsVideoInput"))
                    ||"reference_audio".equals(role)&&Boolean.FALSE.equals(capability.get("supportsReferenceAudio")))forbid(result,path,field);
            if(field.getMinimum()!=null&&field.getMaximum()!=null&&field.getMinimum().compareTo(field.getMaximum())>0)fail(path+"参数范围无交集");
        }
        result.setPresentation(presentation);
        result.getBindings().stream().filter(item->Objects.equals(item.getCode(),route.getCode()))
                .forEach(item->{
                    item.setPresentation(new LinkedHashMap<>(presentation));
                    if(Boolean.FALSE.equals(capability.get("supportsAudio"))) {
                        Map<String,Object> effectiveCapability=new LinkedHashMap<>(capability);effectiveCapability.put("defaultAudio",false);item.setCapability(effectiveCapability);
                    }
                });
        // 父对象可能携带历史子字段默认值，不能重新注入已被路由排除的值。
        Set<String> forbidden=new HashSet<>();
        if(result.getRules()!=null)result.getRules().stream().filter(rule->"路由未开放此参数".equals(rule.getLabel()))
                .forEach(rule->rule.getActions().forEach(action->forbidden.add(action.getField())));
        cleanObjectDefaults(result.getParameters(),"",forbidden);
        return result;
    }

    /** 只有显式开放且未声明枚举的字符串尺寸字段承载自定义像素，不绕过已有枚举约束。 */
    static boolean isCustomPixelSize(String path,ModelParameter field,Map<String,Object> capability) {
        return Boolean.TRUE.equals(capability.get("allowCustomWH"))
                && ("size".equals(path)||"options.size".equals(path))
                && field!=null&&"string".equals(field.getType())
                && (field.getChoices()==null||field.getChoices().isEmpty());
    }

    private static void restrictChoices(ModelCapabilityDefinition definition,Map<String,ModelParameter> fields,
            Map<String,Object> capability,String key,String defaultKey,List<String> paths) {
        if(!capability.containsKey(key)||capability.get(key)==null)return;
        if(!(capability.get(key) instanceof List<?>))fail(key+"必须是列表");
        List<?> declared=(List<?>)capability.get(key);
        for(String path:paths) {
            ModelParameter field=fields.get(path);if(field==null)continue;
            if(declared.isEmpty()){field.setChoices(null);forbid(definition,path,field);continue;}
            List<Object> allowed=new ArrayList<>();
            for(Object value:declared) {
                Object canonical=value;
                if(field.getChoices()!=null&&!field.getChoices().isEmpty()) {
                    canonical=field.getChoices().stream().filter(item->same(item,value)).findFirst().orElse(null);
                    if(canonical==null)continue;
                }
                BigDecimal numeric=decimal(canonical);
                if(Set.of("integer","number").contains(field.getType())&&numeric!=null
                        &&(field.getMinimum()!=null&&numeric.compareTo(field.getMinimum())<0||field.getMaximum()!=null&&numeric.compareTo(field.getMaximum())>0))continue;
                if(!allowed.contains(canonical))allowed.add(canonical);
            }
            if(allowed.isEmpty())fail(path+"参数枚举与路由能力无交集");
            field.setChoices(allowed);
            Object requested=capability.get(defaultKey);
            Object fallback=field.getDefaultValue();
            Object preferred=requested!=null?requested:fallback;
            if(preferred!=null)field.setDefaultValue(allowed.stream().filter(value->same(value,preferred)).findFirst().orElse(allowed.get(0)));
        }
    }
    private static void forbid(ModelCapabilityDefinition definition,String path,ModelParameter field) {
        field.setRequired(false);field.setDefaultValue(null);
        var condition=new ModelParameterRule.Condition();condition.setField(path);condition.setOperator("present");
        var action=new ModelParameterRule.Action();action.setField(path);action.setOperator("forbidden");
        var rule=new ModelParameterRule();rule.setLabel("路由未开放此参数");rule.setMatch("all");rule.setConditions(List.of(condition));rule.setActions(List.of(action));
        List<ModelParameterRule> rules=new ArrayList<>(definition.getRules()==null?List.of():definition.getRules());rules.add(rule);definition.setRules(rules);
    }
    private static void disable(ModelCapabilityDefinition definition,Map<String,ModelParameter> fields,
            Map<String,Object> capability,Map<String,Object> presentation,String key,List<String> paths) {
        if(!Boolean.FALSE.equals(capability.get(key))&&!Boolean.FALSE.equals(presentation.get(key)))return;
        presentation.put(key,false);
        String defaultKey=switch(key){case "supportsSizePreset"->"defaultSizeCode";case "supportsAspectRatio"->"defaultAspectRatio";default->"defaultDurationSeconds";};
        presentation.put(defaultKey,null);
        for(String path:paths)if(fields.containsKey(path))forbid(definition,path,fields.get(path));
    }
    private static void alignDefaults(Map<String,ModelParameter> fields,Map<String,Object> capability,
            Map<String,Object> presentation,String capabilityKey,String presentationKey,List<String> paths) {
        ModelParameter first=paths.stream().map(fields::get).filter(Objects::nonNull).findFirst().orElse(null);
        if(first==null)return;
        String optionsKey=switch(capabilityKey){case "defaultSize"->"sizeOptions";case "defaultAspectRatio"->"aspectRatioOptions";default->"durationOptions";};
        if(capability.get(optionsKey) instanceof List<?> values&&values.isEmpty()) {presentation.put(presentationKey,null);return;}
        Object preferred=capability.get(capabilityKey);
        if(preferred==null)preferred=presentation.get(presentationKey);
        if(preferred==null)preferred=first.getDefaultValue();
        if(first.getChoices()!=null&&!first.getChoices().isEmpty()&&preferred!=null) {
            Object requested=preferred;preferred=first.getChoices().stream().filter(value->same(value,requested)).findFirst().orElse(first.getChoices().get(0));
        }
        if(preferred!=null) {
            for(String path:paths)if(fields.containsKey(path))fields.get(path).setDefaultValue(preferred);
            presentation.put(presentationKey,preferred);
        }
    }
    private static void clampDefault(ModelParameter field) {
        BigDecimal value=decimal(field.getDefaultValue());
        if(value!=null&&field.getMaximum()!=null&&value.compareTo(field.getMaximum())>0)field.setDefaultValue(field.getMaximum().intValueExact());
    }
    @SuppressWarnings("unchecked") private static void cleanObjectDefaults(List<ModelParameter> fields,String prefix,Set<String> forbidden) {
        if(fields==null)return;
        for(ModelParameter field:fields)if(field.getProperties()!=null) {
            if(field.getDefaultValue() instanceof Map<?,?> raw) {
                Map<String,Object> values=new LinkedHashMap<>((Map<String,Object>)raw);
                for(ModelParameter child:field.getProperties())if(values.containsKey(child.getName())) {
                    if(forbidden.contains(prefix+field.getName()+"."+child.getName()))values.remove(child.getName());
                    else if(child.getDefaultValue()!=null)values.put(child.getName(),child.getDefaultValue());
                    else if(child.getChoices()!=null&&!child.getChoices().isEmpty()&&child.getChoices().stream().noneMatch(item->same(item,values.get(child.getName()))))values.remove(child.getName());
                }
                field.setDefaultValue(values);
            }
            cleanObjectDefaults(field.getProperties(),prefix+field.getName()+".",forbidden);
        }
    }
    private static void collect(List<ModelParameter> fields,String prefix,Map<String,ModelParameter> result){if(fields!=null)for(var field:fields){result.put(prefix+field.getName(),field);collect(field.getProperties(),prefix+field.getName()+".",result);}}
    private static BigDecimal decimal(Object value){try{return value==null?null:new BigDecimal(value.toString());}catch(Exception invalid){return null;}}
    private static BigDecimal positive(Object value){BigDecimal number=decimal(value);return number!=null&&number.signum()>0?number:null;}
    private static BigDecimal tighterMin(BigDecimal left,BigDecimal right){return left==null?right:right==null?left:left.max(right);}
    private static BigDecimal tighterMax(BigDecimal left,BigDecimal right){return left==null?right:right==null?left:left.min(right);}
    private static boolean same(Object left,Object right){BigDecimal a=decimal(left),b=decimal(right);if(a!=null&&b!=null)return a.compareTo(b)==0;return left instanceof String aText&&right instanceof String bText?aText.trim().equalsIgnoreCase(bText.trim()):Objects.equals(left,right);}
    private static void fail(String message){throw MediaPreparationErrorCode.MODEL_CAPABILITY_INVALID.reject(message);}
}
