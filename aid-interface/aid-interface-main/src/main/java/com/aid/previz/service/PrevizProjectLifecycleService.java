package com.aid.previz.service;

import com.aid.common.exception.ServiceException;
import com.aid.previz.mapper.PrevizProjectLifecycleMapper;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reversible owner-only tombstones; immutable files and financial records are retained. */
@Service @RequiredArgsConstructor
public class PrevizProjectLifecycleService {
    private final PrevizProjectLifecycleMapper mapper;
    public record Result(String projectId,String operation,String appliedRevision,String currentRevision,boolean deleted,boolean replay) { }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Result change(Long actor,String project,String operation,long expected,String request) {
        if(actor==null||actor<1)throw new ServiceException("请先登录",401);
        if(project==null||!project.matches("[A-Za-z0-9_-]{1,100}")||request==null||!request.matches("[A-Za-z0-9_-]{1,100}")
                ||operation==null||!Set.of("DELETE","RESTORE").contains(operation)||expected<0)throw new IllegalArgumentException("项目操作参数无效");
        var current=mapper.lock(project);
        if(current==null||!Objects.equals(actor,current.getOwnerUserId()))throw new ServiceException("项目不存在或无权管理",403);
        String digest=cn.hutool.crypto.SecureUtil.sha256(actor+":"+project+":"+operation+":"+expected);
        var receipt=mapper.receipt(project,request);
        if(receipt!=null){
            if(!Objects.equals(actor,receipt.getActorId())||!digest.equals(receipt.getRequestDigest()))throw new ServiceException("操作标识已用于不同请求",409);
            return new Result(project,operation,String.valueOf(receipt.getAppliedRevision()),String.valueOf(current.getRevision()),"2".equals(current.getDelFlag()),true);
        }
        boolean deleting="DELETE".equals(operation);
        if(current.getRevision()==null||current.getRevision()!=expected||expected==Long.MAX_VALUE
                ||!(deleting?"0":"2").equals(current.getDelFlag()))throw new ServiceException("项目状态已变化，请刷新后操作",409);
        if(deleting&&(mapper.activeRuntime(project)!=0||mapper.activeCompute(project)!=0))
            throw new ServiceException("项目仍有运行中或待核实任务，请先处理任务后再移入回收站",409);
        if(mapper.transition(project,actor,expected,deleting?"2":"0")!=1)throw new ServiceException("项目版本冲突，请刷新",409);
        receipt=new PrevizProjectLifecycleMapper.Receipt();receipt.setProjectId(project);receipt.setRequestId(request);receipt.setActorId(actor);
        receipt.setOperation(operation);receipt.setRequestDigest(digest);receipt.setAppliedRevision(expected+1);receipt.setDeleted(deleting?1:0);
        if(mapper.insert(receipt)!=1)throw new IllegalStateException("项目操作回执未保存");
        return new Result(project,operation,String.valueOf(expected+1),String.valueOf(expected+1),deleting,false);
    }
    public Map<String,Object> trash(Long actor,String before,int limit){
        if(actor==null||actor<1)throw new ServiceException("请先登录",401);
        if(limit<1||limit>50||before!=null&&!before.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("项目分页无效");
        var rows=mapper.trash(actor,before,limit);Map<String,Object> result=new LinkedHashMap<>();
        result.put("items",rows.stream().map(row->Map.of("id",row.getId(),"title",row.getTitle(),"role","OWNER","revision",String.valueOf(row.getRevision()))).toList());
        result.put("nextCursor",rows.size()==limit?rows.get(rows.size()-1).getId():null);return result;
    }
}
