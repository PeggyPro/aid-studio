package com.aid.previz.service;

import com.aid.previz.workspace.*;
import com.aid.runtime.tool.*;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Auxiliary workspace files are not executable build inputs. Only the bound project is accessible. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(prefix="aid.previz.runtime",name="enabled",havingValue="true")
public final class PrevizFileToolProvider implements RuntimeToolProvider {
    public static final String READ="aid.previz.file-read",WRITE="aid.previz.file-write",COPY="aid.previz.file-copy",LIST="aid.previz.file-list",GLOB="aid.previz.file-glob",EDIT="aid.previz.file-edit",GREP="aid.previz.file-grep";
    private final PrevizWorkspaceFiles files;
    private final PrevizProjectService projects;
    private static final String VERSION="\"workspaceVersion\":{\"type\":[\"string\",\"null\"],\"pattern\":\"^[a-f0-9]{64}$\"}";
    private static final String EXPECTED="\"expectedVersion\":{\"type\":[\"string\",\"null\"],\"pattern\":\"^[a-f0-9]{64}$\"}";
    private static final String PATH="{\"type\":\"string\",\"pattern\":\"^/workspace/files(?:/.*)?$\",\"maxLength\":260}";
    @Override public List<RuntimeToolRegistry.Descriptor> descriptors(){return List.of(
        tool(READ,"读取项目通用文件","按行读取/workspace/files的固定版本UTF-8文本，默认offset=1/limit=200，最大1000行和100KiB输出，检查截断标记。不是读取Skill资源或构建源码区。",List.of("path"),"\"path\":"+PATH+","+VERSION+",\"offset\":{\"type\":\"integer\",\"minimum\":1},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}"),
        tool(LIST,"列出项目通用文件","列出/workspace/files目录直接子项，最多1000项；返回workspaceVersion用于后续并发校验。",List.of("path"),"\"path\":"+PATH+","+VERSION),
        tool(GLOB,"查找项目通用文件","在/workspace/files按glob查找；支持*、**、?、字符集合及最多64组花括号展开。最多1000项，默认跳过依赖目录，明确包含时可查。",List.of("pattern"),"\"pattern\":"+PATH+","+VERSION),
        tool(WRITE,"写入项目通用文本","完整UTF-8文本写入/workspace/files，空字符串清空，单文件最大10MiB。expectedVersion来自目录/写入回执，首次null；版本冲突不覆盖。辅助文件不会自动执行或进入构建白名单。",List.of("path","content","expectedVersion"),"\"path\":"+PATH+",\"content\":{\"type\":\"string\",\"maxLength\":10485760},"+EXPECTED),
        tool(COPY,"精确复制项目文件","在/workspace/files内按字节复制文本或二进制，不经模型改写。sourcePath支持单文件/glob，glob时targetPath是目录并保留静态模式前缀后的层级。单文件10MiB，最多50文件，超数量写前拒绝。整批非事务；返回逐文件结果，发生冲突停止后续，不自动重放已成功文件。",List.of("sourcePath","targetPath","expectedVersion"),"\"sourcePath\":"+PATH+",\"targetPath\":"+PATH+","+EXPECTED),
        tool(EDIT,"精确编辑项目文本","对/workspace/files固定版本的单文件执行有序old/new精确替换；replace_all默认false且要求唯一匹配，new空串删除。整组检查完成后一次提交，失败不落盘，保留其他文件。",List.of("path","edit","expectedVersion"),"\"path\":"+PATH+","+EXPECTED+",\"edit\":{\"type\":\"array\",\"minItems\":1,\"items\":{\"type\":\"object\",\"required\":[\"old\",\"new\"],\"properties\":{\"old\":{\"type\":\"string\",\"minLength\":1},\"new\":{\"type\":\"string\"},\"replace_all\":{\"type\":\"boolean\"}},\"additionalProperties\":false}}"),
        tool(GREP,"搜索项目通用文本","RE2正文搜索/workspace/files的固定版本。上下文各0至10行，二进制跳过；最多1000条/100KiB，扫描时间有界，检查truncated。include为文件名或路径glob，不支持花括号。",List.of("path","pattern"),"\"path\":"+PATH+","+VERSION+",\"pattern\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":8192},\"include\":{\"type\":\"string\"},\"before_context\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":10},\"after_context\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":10}"));}
    private RuntimeToolRegistry.Descriptor tool(String code,String name,String description,List<String> required,String properties){
        String input="{\"type\":\"object\",\"required\":"+JSON.toJSONString(required)+",\"properties\":{"+properties+"},\"additionalProperties\":false}",output="{\"type\":\"object\"}";boolean write=mutating(code);
        return new RuntimeToolRegistry.Descriptor(code,"1.0.0",cn.hutool.crypto.SecureUtil.sha256(input+"\n"+output),name,description,input,output,write?"DATABASE_WRITE":"READ",false,write?"IDEMPOTENCY_KEY":"REQUEST_DIGEST",false,write?"RECONCILE_RECEIPT":"REPLAY_SAFE",List.of("previz.project"));
    }
    private static boolean mutating(String code){return WRITE.equals(code)||COPY.equals(code)||EDIT.equals(code);}
    @Override public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context,RuntimeToolRegistry.Descriptor descriptor){return context!=null&&context.getUserId()!=null&&"PREVIZ_3DBOX".equals(context.getModuleCode())?RuntimeToolAvailability.enabled():RuntimeToolAvailability.unavailable("PREVIZ_PROJECT_REQUIRED");}
    @Override public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor){return mutating(descriptor.code())?OutputReferenceKind.DURABLE_RECEIPT:OutputReferenceKind.OBSERVATION;}
    @Override public boolean requiresExecutionCheckpoint(RuntimeToolRegistry.Descriptor descriptor){return mutating(descriptor.code());}
    @Override public String activitySubject(RuntimeToolRegistry.Descriptor descriptor){return mutating(descriptor.code())?"项目文件保存":"项目文件读取";}
    private String project(RuntimeToolInvocation invocation){if(invocation==null||invocation.getUserId()==null||invocation.getRunId()==null)throw new ServiceException("缺少项目运行身份");String id=PrevizRuntimeToolProvider.bound(invocation).getString("previzProjectId");projects.requireWrite(invocation.getUserId(),id);return id;}
    @Override public RuntimeToolResult execute(RuntimeToolInvocation invocation){
        String project=project(invocation);JSONObject args=JSON.parseObject(invocation.getArgumentsJson());if(args==null)throw new ServiceException("文件工具参数无效");
        try{
            String version=args.getString("workspaceVersion");if(version==null&&!mutating(invocation.getToolCode()))version=files.current(invocation.getUserId(),project);
            JSONObject result=switch(invocation.getToolCode()){
                case READ -> files.read(invocation.getUserId(),project,version,args.getString("path"),args.getIntValue("offset",1),args.getIntValue("limit",200));
                case LIST -> files.list(invocation.getUserId(),project,version,args.getString("path"));
                case GLOB -> files.glob(invocation.getUserId(),project,version,args.getString("pattern"));
                case WRITE -> files.writeText(invocation.getUserId(),project,args.getString("expectedVersion"),args.getString("path"),args.getString("content"));
                case COPY -> files.copy(invocation.getUserId(),project,args.getString("expectedVersion"),args.getString("sourcePath"),args.getString("targetPath"));
                case EDIT -> files.edit(invocation.getUserId(),project,args.getString("expectedVersion"),args.getString("path"),args.getJSONArray("edit"));
                case GREP -> files.grep(invocation.getUserId(),project,version,args.getString("path"),args.getString("pattern"),args.getString("include"),args.getIntValue("before_context",0),args.getIntValue("after_context",0));
                default -> throw new ServiceException("未知文件工具");
            };
            result.put("projectId",project);RuntimeToolResult outcome=RuntimeToolResult.succeeded(JSON.toJSONString(result,JSONWriter.Feature.WriteMapNullValue));
            if(COPY.equals(invocation.getToolCode())&&!result.getJSONArray("failed").isEmpty()){outcome.setStatus("FAILED");outcome.setErrorCode("WORKSPACE_COPY_PARTIAL");outcome.setSafeSummary("部分文件未复制，已成功文件保留，请核对逐文件回执");outcome.setRetryable(false);}
            else outcome.setSafeSummary(mutating(invocation.getToolCode())?"项目文件已保存":"项目文件已读取");return outcome;
        }catch(java.io.IOException failure){
            String code=Objects.toString(failure.getMessage(),"");
            if(code.equals("WORKSPACE_VERSION_CONFLICT")||code.startsWith("SOURCE_VERSION_CONFLICT:")||code.equals("WORKSPACE_QUOTA_EXCEEDED")||code.equals("WORKSPACE_STORAGE_QUOTA_EXCEEDED")||code.equals("WORKSPACE_STORAGE_COUNT_EXCEEDED")){
                RuntimeToolResult rejected=new RuntimeToolResult();rejected.setStatus("FAILED");rejected.setRetryable(false);rejected.setErrorCode(code.contains("CONFLICT")?"WORKSPACE_VERSION_CONFLICT":"WORKSPACE_QUOTA_EXCEEDED");rejected.setSafeSummary(code.contains("CONFLICT")?"工作区版本已变化，本次未覆盖文件，请重新读取目录":"工作区文件容量达到限制，本次未发布文件");return rejected;
            }
            if(mutating(invocation.getToolCode()))return RuntimeToolResult.unknown("WORKSPACE_FILE_RECEIPT_PENDING","文件操作状态待核对，不能重复覆盖");throw new ServiceException("项目文件不可读取，请核对路径、版本和权限");
        }
    }
    @Override public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared){
        RuntimeToolInvocation identity=new RuntimeToolInvocation();identity.setUserId(prepared.getUserId());identity.setRunId(prepared.getRunId());identity.setContextManifestJson(prepared.getContextManifestJson());String project=project(identity);
        if(!mutating(prepared.getToolCode())){identity.setToolCode(prepared.getToolCode());identity.setArgumentsJson(prepared.getPreparedPayloadJson());return execute(identity);}
        JSONObject args=JSON.parseObject(prepared.getPreparedPayloadJson());
        try{
            String current=files.current(prepared.getUserId(),project);
            if(WRITE.equals(prepared.getToolCode())||EDIT.equals(prepared.getToolCode())){
                byte[] actual=files.readBytes(prepared.getUserId(),project,current,args.getString("path")),expected=WRITE.equals(prepared.getToolCode())?args.getString("content").getBytes(StandardCharsets.UTF_8):PrevizSourceEdits.apply(files.readBytes(prepared.getUserId(),project,args.getString("expectedVersion"),args.getString("path")),args.getJSONArray("edit"),PrevizFileContentStore.MAX_FILE_BYTES).bytes();
                if(Arrays.equals(actual,expected))return RuntimeToolResult.succeeded(JSONObject.of("projectId",project,"path",args.get("path"),"workspaceVersion",current,"etag",PrevizFileContentStore.digest(actual),"size",actual.length,"created",false).toJSONString());
            }
            if(COPY.equals(prepared.getToolCode())){
                JSONArray copied=new JSONArray();var targets=files.copyTargets(prepared.getUserId(),project,args.getString("expectedVersion"),args.getString("sourcePath"),args.getString("targetPath"));
                for(var target:targets.entrySet()){
                    byte[] expected=files.readBytes(prepared.getUserId(),project,args.getString("expectedVersion"),"/workspace/files/"+target.getKey());
                    byte[] actual=files.readBytes(prepared.getUserId(),project,current,"/workspace/files/"+target.getValue());
                    if(!Arrays.equals(expected,actual))return RuntimeToolResult.unknown("WORKSPACE_FILE_RECEIPT_PENDING","复制目标已变化，不能自动重复覆盖");
                    copied.add(JSONObject.of("path","/workspace/files/"+target.getValue(),"etag",PrevizFileContentStore.digest(actual),"size",actual.length,"workspaceVersion",current,"created",false));
                }
                return RuntimeToolResult.succeeded(JSONObject.of("projectId",project,"workspaceVersion",current,"succeeded",copied,"failed",new JSONArray(),"atomicBatch",false).toJSONString());
            }
        }
        catch(java.io.IOException|IllegalArgumentException invalid){return RuntimeToolResult.unknown("WORKSPACE_FILE_RECEIPT_PENDING","文件回执尚不可核验");}
        return RuntimeToolResult.unknown("WORKSPACE_FILE_RECEIPT_PENDING","文件操作回执待核对，不重复复制已成功文件");
    }
}
