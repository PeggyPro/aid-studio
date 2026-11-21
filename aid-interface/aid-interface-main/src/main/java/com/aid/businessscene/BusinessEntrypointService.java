package com.aid.businessscene;

import com.aid.businessscene.domain.AidBusinessEntrypoint;
import com.aid.businessscene.mapper.AidBusinessEntrypointMapper;
import com.aid.model.service.IAiModelBusinessService;
import com.aid.model.vo.AiModelVO;
import com.aid.compute.ComputeWorkerClient;
import com.aid.compute.ComputeResourcePolicy;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Scene bindings reference existing immutable packages and the existing model/price catalog. */
@Service
public class BusinessEntrypointService {
    private final AidBusinessEntrypointMapper rows;private final IAiModelBusinessService models;
    private final Map<String,BusinessEntrypointProvider> providers;
    public BusinessEntrypointService(AidBusinessEntrypointMapper rows,IAiModelBusinessService models,List<BusinessEntrypointProvider> providers){
        this.rows=rows;this.models=models;var index=new HashMap<String,BusinessEntrypointProvider>();
        for(var provider:providers)if(index.put(provider.code(),provider)!=null)throw new IllegalStateException("Duplicate business entrypoint provider");this.providers=Map.copyOf(index);
    }
    public record Binding(String providerCode,String kind,String code,String versionId,String packageDigest,boolean defaultEntry,int sortOrder) { }
    public record Item(Binding binding,BusinessEntrypointProvider.Descriptor entrypoint,List<String> allowedModelCodes,boolean available,String unavailableReason) { }
    public record View(String sceneCode,String revision,List<Item> bindings,List<AiModelVO> models) { }
    public record Option(String providerCode,BusinessEntrypointProvider.Descriptor entrypoint) { }
    public record Save(String sceneCode,String expectedRevision,List<Binding> bindings) { }
    private List<AidBusinessEntrypoint> stored(String code){return rows.selectList(Wrappers.<AidBusinessEntrypoint>lambdaQuery().eq(AidBusinessEntrypoint::getFuncCode,code)
        .orderByAsc(AidBusinessEntrypoint::getSortOrder).orderByAsc(AidBusinessEntrypoint::getProviderCode).orderByAsc(AidBusinessEntrypoint::getEntryKind).orderByAsc(AidBusinessEntrypoint::getEntryCode));}
    private Binding binding(AidBusinessEntrypoint r){return new Binding(r.getProviderCode(),r.getEntryKind(),r.getEntryCode(),r.getVersionId(),r.getPackageDigest(),Boolean.TRUE.equals(r.getDefaultEntry()),r.getSortOrder());}
    public List<Option> options(String scene){ComputeResourcePolicy.key(scene);return providers.values().stream().sorted(Comparator.comparing(BusinessEntrypointProvider::code))
        .flatMap(provider->provider.options(scene).stream().map(option->new Option(provider.code(),option))).toList();}
    public View read(String scene){
        ComputeResourcePolicy.key(scene);var stored=stored(scene);var available=models.listAvailableModelsByFuncCode(scene);
        Set<String> codes=new HashSet<>();available.forEach(m->codes.add(m.getModelCode()));var items=new ArrayList<Item>();
        for(var row:stored){var b=binding(row);try{
            var descriptor=provider(b.providerCode()).resolve(scene,new BusinessEntrypointProvider.Reference(b.kind(),b.code(),b.versionId()));
            if(!b.packageDigest().equals(descriptor.packageDigest()))throw new ServiceException("固定版本摘要不一致");
            var allowed=descriptor.selectableModelCodes().stream().filter(codes::contains).toList();
            items.add(new Item(b,descriptor,allowed,!allowed.isEmpty(),allowed.isEmpty()?"业务模型池与固定版本没有可用交集":null));
        }catch(ServiceException unavailable){items.add(new Item(b,null,List.of(),false,"固定版本或执行入口当前不可用"));}}
        return new View(scene,ComputeWorkerClient.digest(stored.stream().map(this::binding).toList()),List.copyOf(items),available);
    }
    private BusinessEntrypointProvider provider(String code){var provider=providers.get(code);if(provider==null)throw new ServiceException("执行入口适配器不可用",409);return provider;}
    @Transactional(rollbackFor=Exception.class)
    public View save(Save input,String actor){
        if(input==null)throw new ServiceException("场景入口参数不能为空",400);
        ComputeResourcePolicy.key(input.sceneCode());if(rows.lockScene(input.sceneCode())==null)throw new ServiceException("业务场景不存在或已删除",404);
        var prior=stored(input.sceneCode()).stream().map(this::binding).toList();
        if(input.bindings()==null||input.bindings().size()>100)throw new ServiceException("场景入口数量无效",400);
        if(!ComputeWorkerClient.digest(prior).equals(input.expectedRevision())){
            if(prior.equals(input.bindings()))return read(input.sceneCode());throw new ServiceException("业务场景绑定已被更新，请重新读取",409);
        }
        var seen=new HashSet<String>();var defaults=new HashSet<String>();
        Set<String> modelCodes=new HashSet<>();models.listAvailableModelsByFuncCode(input.sceneCode()).forEach(m->modelCodes.add(m.getModelCode()));
        for(var b:input.bindings()){
            if(b==null)throw new ServiceException("入口绑定不能为空",400);
            ComputeResourcePolicy.key(b.providerCode());ComputeResourcePolicy.key(b.code());
            if(b.kind()==null||!Set.of("AGENT","SKILL").contains(b.kind())||b.versionId()==null||!b.versionId().matches("[1-9][0-9]{0,18}")||b.sortOrder()<0||b.sortOrder()>10000
                ||b.packageDigest()==null||!b.packageDigest().matches("[a-f0-9]{64}")||!seen.add(b.providerCode()+":"+b.kind()+":"+b.code()))throw new ServiceException("入口绑定字段无效或重复",400);
            if(b.defaultEntry()&&!defaults.add(b.kind()))throw new ServiceException("每类入口只能配置一个默认项",400);
            try{Long.parseLong(b.versionId());}catch(NumberFormatException invalid){throw new ServiceException("入口版本标识超出范围",400);}
            var resolved=provider(b.providerCode()).resolve(input.sceneCode(),new BusinessEntrypointProvider.Reference(b.kind(),b.code(),b.versionId()));
            if(!b.packageDigest().equals(resolved.packageDigest())||resolved.selectableModelCodes().stream().noneMatch(modelCodes::contains))throw new ServiceException("固定入口摘要或业务可用模型不匹配",409);
        }
        var affected=new TreeSet<String>();prior.forEach(b->affected.add(b.providerCode()));input.bindings().forEach(b->affected.add(b.providerCode()));
        for(String code:affected)if(providers.containsKey(code))provider(code).activate(input.sceneCode(),input.bindings().stream().filter(b->code.equals(b.providerCode()))
            .map(b->new BusinessEntrypointProvider.Selection(new BusinessEntrypointProvider.Reference(b.kind(),b.code(),b.versionId()),b.defaultEntry())).toList(),actor);
        rows.delete(Wrappers.<AidBusinessEntrypoint>lambdaQuery().eq(AidBusinessEntrypoint::getFuncCode,input.sceneCode()));
        for(var b:input.bindings()){var row=new AidBusinessEntrypoint();row.setFuncCode(input.sceneCode());row.setProviderCode(b.providerCode());row.setEntryKind(b.kind());row.setEntryCode(b.code());
            row.setVersionId(b.versionId());row.setPackageDigest(b.packageDigest());row.setDefaultEntry(b.defaultEntry());row.setSortOrder(b.sortOrder());row.setUpdateBy(actor);row.setUpdateTime(new Date());rows.insert(row);}
        return read(input.sceneCode());
    }
}
