package com.aid.previz.config;

import com.aid.businessscene.BusinessEntrypointProvider;
import com.aid.runtime.domain.*;
import com.aid.runtime.mapper.*;
import com.aid.skill.domain.*;
import com.aid.skill.mapper.*;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import java.util.*;

/** Private Runtime adapter: projects only safe package identities into generic business configuration. */
@Component
public class PrevizBusinessEntrypointProvider implements BusinessEntrypointProvider {
    private final AidRuntimeAgentMapper agents;private final AidRuntimeAgentVersionMapper versions;
    private final AidRuntimeAgentSkillRefMapper refs;private final AidSkillMapper skills;private final AidSkillVersionMapper skillVersions;
    private final com.aid.runtime.service.RuntimeAgentVersionLoader loader;
    private final com.aid.skill.service.SkillRuntimeEntrypointReadinessService readiness;
    private final com.aid.runtime.service.RuntimeAgentAdminService administration;
    private final AidRuntimeModuleAgentBindingMapper modules;
    private final com.aid.runtime.service.RuntimeAgentAdminAudit audit;
    public PrevizBusinessEntrypointProvider(AidRuntimeAgentMapper agents,AidRuntimeAgentVersionMapper versions,AidRuntimeAgentSkillRefMapper refs,AidSkillMapper skills,AidSkillVersionMapper skillVersions,
        com.aid.runtime.service.RuntimeAgentVersionLoader loader,com.aid.skill.service.SkillRuntimeEntrypointReadinessService readiness,
        com.aid.runtime.service.RuntimeAgentAdminService administration,AidRuntimeModuleAgentBindingMapper modules,com.aid.runtime.service.RuntimeAgentAdminAudit audit){
        this.agents=agents;this.versions=versions;this.refs=refs;this.skills=skills;this.skillVersions=skillVersions;this.loader=loader;this.readiness=readiness;this.administration=administration;this.modules=modules;this.audit=audit;
    }
    public void activate(String scene,List<Selection> selections,String actor){
        String module=scene.toUpperCase(Locale.ROOT);
        var current=modules.selectOne(Wrappers.<AidRuntimeModuleAgentBinding>lambdaQuery().eq(AidRuntimeModuleAgentBinding::getModuleCode,module).last("LIMIT 1 FOR UPDATE"));
        if(selections.isEmpty()){
            if(current!=null&&"ACTIVE".equals(current.getStatus())){current.setStatus("INACTIVE");current.setBindingRevision(current.getBindingRevision()+1);
                current.setUpdateBy(actor);current.setUpdateTime(new Date());modules.updateById(current);audit.record("scene-binding/deactivate",current.getRuntimeAgentId(),current.getActiveVersionId(),actor);loader.refreshAfterCommit(current.getRuntimeAgentId());}
            return;
        }
        var chosen=selections.stream().filter(s->s.defaultEntry()&&"AGENT".equals(s.reference().kind())).map(Selection::reference).findFirst()
            .orElseThrow(()->new ServiceException("请选择当前业务的默认Agent",400));
        var descriptor=resolve(scene,chosen);var version=agentVersion(scene,chosen);
        for(var selection:selections)if("SKILL".equals(selection.reference().kind())&&!descriptor.dependencies().contains(selection.reference()))
            throw new ServiceException("所选Skill版本必须与默认Agent已发布依赖一致",409);
        if(current!=null&&"ACTIVE".equals(current.getStatus())&&"0".equals(current.getDelFlag())&&version.getAgentId().equals(current.getRuntimeAgentId())&&version.getId().equals(current.getActiveVersionId()))return;
        var request=new com.aid.runtime.dto.RuntimeAgentRequests.ModuleBindingActivate();request.setModuleCode(module);request.setAgentId(version.getAgentId());request.setVersionId(version.getId());
        request.setExpectedBindingRevision(current!=null&&"ACTIVE".equals(current.getStatus())&&"0".equals(current.getDelFlag())?current.getBindingRevision():0L);
        administration.activateModuleBinding(request,actor);
    }
    public String code(){return "runtime-package";}
    private boolean supports(String scene,AidRuntimeAgentVersion version){
        var schema=JSON.parseObject(version.getInputSchemaJson());if(schema==null)return false;
        var properties=schema.getJSONObject("properties");var run=properties==null?null:properties.getJSONObject("run");
        var fields=run==null?null:run.getJSONObject("properties");var module=fields==null?null:fields.getJSONObject("moduleCode");
        var allowed=module==null?null:module.getJSONArray("enum");return allowed!=null&&allowed.contains(scene.toUpperCase(Locale.ROOT));
    }
    private AidRuntimeAgentVersion agentVersion(String scene,Reference reference){
        var version=versions.selectById(Long.valueOf(reference.versionId()));
        var agent=version==null?null:agents.selectById(version.getAgentId());
        if(agent==null||!reference.code().equals(agent.getAgentCode())||!"0".equals(agent.getStatus())||!"0".equals(agent.getDelFlag())
            ||!"PLATFORM".equals(agent.getOwnerType())||!"PUBLIC".equals(agent.getVisibility())||!"0".equals(version.getStatus())
            ||!"0".equals(version.getDelFlag())||!"FROZEN".equals(version.getPublishStatus())||!supports(scene,version))throw new ServiceException("Agent固定版本不适用于当前业务",409);
        return loader.load(version);
    }
    public List<Descriptor> options(String scene){
        var result=new LinkedHashMap<Reference,Descriptor>();
        var active=agents.selectList(Wrappers.<AidRuntimeAgent>lambdaQuery().eq(AidRuntimeAgent::getOwnerType,"PLATFORM").eq(AidRuntimeAgent::getVisibility,"PUBLIC")
            .eq(AidRuntimeAgent::getStatus,"0").eq(AidRuntimeAgent::getDelFlag,"0").isNotNull(AidRuntimeAgent::getCurrentVersionId).orderByAsc(AidRuntimeAgent::getId));
        for(var agent:active){var reference=new Reference("AGENT",agent.getAgentCode(),String.valueOf(agent.getCurrentVersionId()));
            try{var descriptor=resolve(scene,reference);result.put(reference,descriptor);
                for(var child:descriptor.dependencies()){var skill=skillDescriptor(child);result.putIfAbsent(child,skill);}
            }catch(ServiceException unavailable){/* Unsupported modules and inactive packages are not selectable. */}
        }return List.copyOf(result.values());
    }
    public Descriptor resolve(String scene,Reference reference){
        if("SKILL".equals(reference.kind())){
            // A method can only be associated with a scene through an already published Agent dependency.
            var parents=refs.selectList(Wrappers.<AidRuntimeAgentSkillRef>lambdaQuery().eq(AidRuntimeAgentSkillRef::getSkillVersionId,Long.valueOf(reference.versionId()))
                .eq(AidRuntimeAgentSkillRef::getSkillCode,reference.code()));
            for(var ref:parents){var version=versions.selectById(ref.getAgentVersionId());if(version==null)continue;
                var agent=agents.selectById(version.getAgentId());if(agent==null)continue;
                try{agentVersion(scene,new Reference("AGENT",agent.getAgentCode(),String.valueOf(version.getId())));return skillDescriptor(reference);}catch(ServiceException unavailable){}
            }throw new ServiceException("Skill不属于当前场景的已发布依赖",409);
        }
        if(!"AGENT".equals(reference.kind()))throw new ServiceException("未知入口类型",400);
        var version=agentVersion(scene,reference);var agent=agents.selectById(version.getAgentId());
        var dependencies=refs.selectList(Wrappers.<AidRuntimeAgentSkillRef>lambdaQuery().eq(AidRuntimeAgentSkillRef::getAgentVersionId,version.getId()).orderByAsc(AidRuntimeAgentSkillRef::getId))
            .stream().map(ref->new Reference("SKILL",ref.getSkillCode(),String.valueOf(ref.getSkillVersionId()))).toList();
        for(var dependency:dependencies){var child=skillDescriptor(dependency);
            var fixed=refs.selectOne(Wrappers.<AidRuntimeAgentSkillRef>lambdaQuery().eq(AidRuntimeAgentSkillRef::getAgentVersionId,version.getId()).eq(AidRuntimeAgentSkillRef::getSkillCode,dependency.code()));
            if(fixed==null||!fixed.getPackageDigest().equals(child.packageDigest()))throw new ServiceException("Agent固定Skill依赖摘要不一致",409);}
        return descriptor(reference,agent.getName(),version.getPackageDigest(),version.getModelConfigJson(),dependencies);
    }
    private Descriptor skillDescriptor(Reference reference){
        var version=skillVersions.selectById(Long.valueOf(reference.versionId()));var skill=version==null?null:skills.selectById(version.getSkillId());
        if(skill==null||!reference.code().equals(skill.getSkillCode())||!"PLATFORM".equals(skill.getOwnerType())||!"PUBLIC".equals(skill.getVisibility())
            ||!"0".equals(skill.getStatus())||!"0".equals(skill.getDelFlag())||!"0".equals(version.getStatus())||!"0".equals(version.getDelFlag())
            ||!"PUBLISHED".equals(version.getPublishStatus()))throw new ServiceException("Skill固定版本不可用",409);
        readiness.requireReadyMethod(skill,version.getId(),version.getPackageDigest());
        return descriptor(reference,skill.getName(),version.getPackageDigest(),version.getModelConfigJson(),List.of());
    }
    private Descriptor descriptor(Reference ref,String name,String digest,String modelJson,List<Reference> dependencies){
        JSONObject model=JSON.parseObject(modelJson);var codes=model==null?null:model.getList("selectableModelCodes",String.class);
        if(codes==null||codes.isEmpty())throw new ServiceException("固定版本缺少可用模型声明",409);
        return new Descriptor(ref,name,digest,model.getString("defaultModelCode"),List.copyOf(codes),dependencies);
    }
}
