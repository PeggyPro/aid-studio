package com.aid.previz.service;

import cn.hutool.crypto.SecureUtil;
import com.aid.common.exception.ServiceException;
import com.aid.previz.workspace.PrevizSourceStore;
import com.aid.runtime.service.RuntimeToolRegistry;
import com.aid.runtime.tool.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Tools for independent projects. Authority and executable locations never come from the model. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aid.previz.runtime", name = "enabled", havingValue = "true")
public final class PrevizRuntimeToolProvider implements RuntimeToolProvider
{
    public static final String INSPECT = "aid.previz.source-inspect";
    public static final String READ = "aid.previz.source-read";
    public static final String READ_PAGE = "aid.previz.source-read-page";
    public static final String LIST_FILES = "aid.previz.source-list";
    public static final String GLOB_FILES = "aid.previz.source-glob";
    public static final String GREP_FILES = "aid.previz.source-grep";
    public static final String TODO = "aid.previz.progress-list";
    public static final String WRITE = "aid.previz.source-write";
    public static final String EDIT = "aid.previz.source-edit";
    public static final String BUILD = "aid.previz.build";
    public static final String SCOPED_BUILD = "aid.previz.build-scoped";
    public static boolean isBuild(String code){return BUILD.equals(code)||SCOPED_BUILD.equals(code);}
    public static final String PREVIEW = "aid.previz.preview";
    public static final String RENDER_PREVIEW = "aid.previz.render-preview";
    private final PrevizProjectService projects;
    private final PrevizSourceStore sources;
    private final PrevizLocalBuildService builds;
    @jakarta.annotation.Resource private PrevizBuildArtifactWriter buildArtifacts;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private PrevizPreviewService previews;
    @org.springframework.beans.factory.annotation.Autowired private PrevizEditorStore editors;
    private static final String OUTPUT = "{\"type\":\"object\"}";
    private static final String VERSION_FIELD = "\"sourceVersion\":{\"type\":[\"string\",\"null\"],\"pattern\":\"^[a-f0-9]{64}$\"}";
    private static final String OPERATION_FIELD = "\"operation\":{\"type\":\"object\",\"required\":[\"kind\"],\"properties\":{\"kind\":{\"type\":\"string\",\"enum\":[\"ADD\",\"MODIFY\",\"REPLACE_SCOPE\",\"REBUILD_SCENE\"]},\"targetNodeIds\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"maxLength\":100},\"maxItems\":1000},\"outputObjectIds\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"maxLength\":200},\"maxItems\":5000}},\"additionalProperties\":false}";

    @Override public List<RuntimeToolRegistry.Descriptor> descriptors()
    {
        var legacy = List.of(
            descriptor(PREVIEW,"预览已构建三维场景","对成功构建的GLB渲染真实单帧，不重新执行源码。buildStepId来自成功构建回执runtimeStepId。可选机位或观察方向、时间、动画片段和对象检查。后续模型输入自动附上预览图；渲染成功不等于视觉检查完成。只读查询直接调用，无需另建执行计划。", "{\"type\":\"object\",\"required\":[\"buildStepId\"],\"properties\":{\"buildStepId\":{\"type\":\"string\",\"pattern\":\"^[1-9][0-9]{0,18}$\"},\"options\":{\"type\":\"object\",\"properties\":{\"cameraId\":{\"type\":\"string\"},\"view\":{\"type\":\"string\",\"enum\":[\"front\",\"side\",\"top\",\"isometric\"]},\"time\":{\"type\":\"number\",\"minimum\":0},\"animationIndex\":{\"type\":\"integer\",\"minimum\":0},\"width\":{\"type\":\"integer\",\"minimum\":64,\"maximum\":2048},\"height\":{\"type\":\"integer\",\"minimum\":64,\"maximum\":2048},\"inspectIds\":{\"type\":\"array\",\"maxItems\":32,\"items\":{\"type\":\"string\"}},\"exposureEV\":{\"type\":\"number\",\"minimum\":-16,\"maximum\":8}},\"additionalProperties\":false}},\"additionalProperties\":false}",true),
            descriptor(INSPECT, "查看三维工程源码目录", "读取已绑定项目的当前版本与文件目录，不执行源码。", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", true),
            descriptor(READ, "读取三维工程源码", "读取已授权项目中的固定版本文本；sourceVersion省略或为null时，在准备阶段读取并固定当前版本。显式版本保持不变。", "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\",\"maxLength\":240}," + VERSION_FIELD + "},\"additionalProperties\":false}", true),
            descriptor(WRITE, "保存三维工程源码版本", "提交完整文本文件快照；保留未修改文件。基于已观察的目录或写入版本进行并发校验；新项目可首次创建，冲突不覆盖。仅保存，不运行。", "{\"type\":\"object\",\"required\":[\"expectedVersion\",\"files\"],\"properties\":{\"expectedVersion\":{\"type\":[\"string\",\"null\"]},\"files\":{\"type\":\"object\",\"minProperties\":1,\"maxProperties\":125,\"additionalProperties\":{\"type\":\"string\"}}},\"additionalProperties\":false}", false),
            descriptor(BUILD, "构建三维场景", "按用户选择的引擎构建已保存源码版本。该版本只有一个对应引擎入口时由程序绑定，不使用重复填写的文件名；多个候选时entry必须明确选择。Three.js入口为.mjs，导出createScene(THREE)并返回Object3D；每个对象userData.hf_id唯一，动画放root.animations，不使用DOM或WebGLRenderer。Blender入口为.py，使用bpy搭建当前场景，各对象自定义hf_id唯一。程序负责导出GLB并归档构建清单；预览与采用须另行核验。", "{\"type\":\"object\",\"required\":[\"sourceVersion\",\"engine\",\"entry\",\"sceneId\"],\"properties\":{" + VERSION_FIELD + ",\"engine\":{\"type\":\"string\",\"enum\":[\"threejs\",\"blender\"]},\"entry\":{\"type\":\"string\",\"maxLength\":240},\"sceneId\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z0-9_-]{1,100}$\"},\"keepBlend\":{\"type\":\"boolean\"}},\"additionalProperties\":false}", false));
        var descriptors = new java.util.ArrayList<>(legacy);
        descriptors.add(descriptor(SCOPED_BUILD, "按明确范围构建三维对象", "按用户选择的引擎构建已保存源码版本。该版本只有一个对应引擎入口时由程序绑定，不使用重复填写的文件名；多个候选时entry必须明确选择。Three.js入口为.mjs，导出createScene(THREE)并返回Object3D；每个对象userData.hf_id唯一，动画放root.animations，不使用DOM或WebGLRenderer。Blender入口为.py，使用bpy搭建当前场景，各对象自定义hf_id唯一。程序负责导出GLB并归档构建清单；预览与采用须另行核验。", "{\"type\":\"object\",\"required\":[\"sourceVersion\",\"engine\",\"entry\",\"sceneId\",\"operation\"],\"properties\":{" + VERSION_FIELD + ",\"engine\":{\"type\":\"string\",\"enum\":[\"threejs\",\"blender\"]},\"entry\":{\"type\":\"string\",\"maxLength\":240},\"sceneId\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z0-9_-]{1,100}$\"}," + OPERATION_FIELD + ",\"keepBlend\":{\"type\":\"boolean\"}},\"additionalProperties\":false}", false));
        descriptors.add(descriptor(TODO,"整理三维搭建进度清单","校验并回显完整todos列表，空数组清空展示。同一列表只保留第一个in_progress，其余进行中项转为pending。不写入项目文件，不改变任务成功状态，不替代真实执行回执。",
                "{\"type\":\"object\",\"required\":[\"todos\"],\"properties\":{\"todos\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"required\":[\"title\",\"status\"],\"properties\":{\"title\":{\"type\":\"string\",\"minLength\":1},\"status\":{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"done\"]}},\"additionalProperties\":false}}},\"additionalProperties\":false}",true));
        descriptors.add(descriptor(LIST_FILES,"列出三维源码目录","列出固定版本目录直接子项，目录优先；path为项目相对目录或/workspace虚拟根目录。最多1000项，不读取外部磁盘。",
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\"},"+VERSION_FIELD+"},\"additionalProperties\":false}",true));
        descriptors.add(descriptor(GLOB_FILES,"查找三维源码文件","在固定版本按glob查找文件，支持*、**、?、字符集合和最多64项花括号展开；按快照文件时间从新到旧，最多1000项。默认跳过依赖及构建目录，明确写入模式时可查询。",
                "{\"type\":\"object\",\"required\":[\"pattern\"],\"properties\":{\"pattern\":{\"type\":\"string\",\"maxLength\":1024},"+VERSION_FIELD+"},\"additionalProperties\":false}",true));
        descriptors.add(descriptor(GREP_FILES,"搜索三维源码正文","使用RE2正则搜索固定版本文本，不支持环视或反向引用。path必须为目录；include可为文件名或相对路径glob，不支持花括号。上下文各0至10行，最多1000匹配及100KiB输出；二进制跳过，检查truncated。",
                "{\"type\":\"object\",\"required\":[\"pattern\",\"path\"],\"properties\":{\"pattern\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":8192},\"path\":{\"type\":\"string\"},\"include\":{\"type\":\"string\"},\"before_context\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":10},\"after_context\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":10},"+VERSION_FIELD+"},\"additionalProperties\":false}",true));
        descriptors.add(descriptor(EDIT,"精确编辑三维源码",
                "按expectedVersion固定版本对单文件执行有序精确替换。edit非空，每项old为真实原文含空白、new为不同内容（空串删除），replace_all默认false要求唯一匹配。后项读取前项临时结果，全部校验后一次保存新版本；任何错误或版本冲突都不写入。不运行源码，其他文件逐字节保留。",
                "{\"type\":\"object\",\"required\":[\"expectedVersion\",\"path\",\"edit\"],\"properties\":{\"expectedVersion\":{\"type\":\"string\",\"pattern\":\"^[a-f0-9]{64}$\"},\"path\":{\"type\":\"string\",\"maxLength\":240},\"edit\":{\"type\":\"array\",\"minItems\":1,\"items\":{\"type\":\"object\",\"required\":[\"old\",\"new\"],\"properties\":{\"old\":{\"type\":\"string\",\"minLength\":1},\"new\":{\"type\":\"string\"},\"replace_all\":{\"type\":\"boolean\"}},\"additionalProperties\":false}}},\"additionalProperties\":false}",false));
        descriptors.add(descriptor(READ_PAGE,"按页读取三维源码",
                "按固定版本读取UTF-8文本，offset从1开始、默认1；limit默认200、最大1000。返回原文content与行号范围，不把显示行号写入源码。总输出最多100KiB，单行最多2000字符；检查truncated、lineTruncated和nextOffset，必要时继续读取。原完整源码读取工具保留。",
                "{\"type\":\"object\",\"required\":[\"path\"],\"properties\":{\"path\":{\"type\":\"string\",\"maxLength\":240},"+VERSION_FIELD+",\"offset\":{\"type\":\"integer\",\"minimum\":1},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000}},\"additionalProperties\":false}",true));
        descriptors.add(descriptor(RENDER_PREVIEW, "渲染并归档三维预览",
                "从成功构建步骤buildStepId渲染真实单帧并归档PREVIZ_PREVIEW，不重新构建源码。应列入执行计划，可在构建后依赖构建步骤，也可单独规划预览；完成判据使用TECHNICAL_ARCHIVE，不能使用METHOD_RESULT。构建步骤ID从真实回执读取。后续模型输入附上实际图片，图片生成成功不等于画面质量通过。",
                legacy.get(0).inputSchema(), false));
        return List.copyOf(descriptors);
    }
    private RuntimeToolRegistry.Descriptor descriptor(String code, String name, String description, String input, boolean read)
    {
        if(read)description += " 这是观察查询，直接调用，无需为查询另建执行计划；不产生METHOD_RESULT或可归档交付物。";
        return new RuntimeToolRegistry.Descriptor(code, "1.0.0", SecureUtil.sha256(input + "\n" + OUTPUT), name, description,
                input, OUTPUT, read ? "READ" : "DATABASE_WRITE", false, read ? "REQUEST_DIGEST" : "IDEMPOTENCY_KEY",
                false, read ? "REPLAY_SAFE" : "RECONCILE_RECEIPT", List.of("previz.project"));
    }
    @Override public RuntimeToolAvailability availability(RuntimeToolAvailabilityContext context, RuntimeToolRegistry.Descriptor descriptor)
    {
        return context != null && context.getUserId() != null && "PREVIZ_3DBOX".equals(context.getModuleCode())
                ? RuntimeToolAvailability.enabled() : RuntimeToolAvailability.unavailable("PREVIZ_PROJECT_REQUIRED");
    }
    @Override public OutputReferenceKind planningOutputReferenceKind(RuntimeToolRegistry.Descriptor descriptor)
    { return isBuild(descriptor.code()) || RENDER_PREVIEW.equals(descriptor.code()) ? OutputReferenceKind.RUNTIME_ARTIFACT
            : WRITE.equals(descriptor.code()) || EDIT.equals(descriptor.code()) ? OutputReferenceKind.DURABLE_RECEIPT : OutputReferenceKind.OBSERVATION; }

    @Override public String planningArtifactType(RuntimeToolRegistry.Descriptor descriptor)
    { return isBuild(descriptor.code()) ? "PREVIZ_SCENE" : RENDER_PREVIEW.equals(descriptor.code()) ? "PREVIZ_PREVIEW" : null; }

    @Override public boolean requiresExecutionCheckpoint(RuntimeToolRegistry.Descriptor descriptor)
    { return isBuild(descriptor.code()) || PREVIEW.equals(descriptor.code()) || RENDER_PREVIEW.equals(descriptor.code()); }

    @Override public String activitySubject(RuntimeToolRegistry.Descriptor descriptor) {
        return switch (descriptor.code()) {
            case INSPECT -> "工程目录查询";
            case TODO -> "搭建进度清单整理";
            case READ, READ_PAGE, LIST_FILES, GLOB_FILES, GREP_FILES -> "工程源码读取";
            case WRITE, EDIT -> "工程源码保存";
            case BUILD, SCOPED_BUILD -> "三维场景构建";
            case PREVIEW, RENDER_PREVIEW -> "三维场景预览";
            default -> null;
        };
    }

    @Override public RuntimeToolResult executePrepared(RuntimeToolPreparedInvocation prepared,
            com.aid.billing.dto.BillingBudgetContext budget) {
        if (isBuild(prepared.getToolCode()) || RENDER_PREVIEW.equals(prepared.getToolCode())) requireBuildRuntime(prepared);
        RuntimeToolResult result = RuntimeToolProvider.super.executePrepared(prepared, budget);
        return isBuild(prepared.getToolCode()) || RENDER_PREVIEW.equals(prepared.getToolCode()) ? buildArtifacts.archive(prepared, result) : result;
    }

    @Override public RuntimeToolPreparation prepare(RuntimeToolInvocation invocation)
    {
        String project = project(invocation);
        JSONObject args = JSON.parseObject(invocation.getArgumentsJson());
        if (args == null) throw new ServiceException("三维工具参数无效");
        if (List.of(READ, READ_PAGE, LIST_FILES, GLOB_FILES, GREP_FILES).contains(invocation.getToolCode())
                && args.getString("sourceVersion") == null) {
            // This is a read observation, not a write CAS. Freeze the authorized head once
            // in the durable preparation; execution/replay must not follow later changes.
            try {
                String version = sources.currentVersion(invocation.getUserId(), project);
                if (version != null) args.put("sourceVersion", version);
            } catch (java.io.IOException unavailable) {
                throw new ServiceException("源码目录读取未完成，请核对存储状态");
            }
        }
        if (EDIT.equals(invocation.getToolCode())) {
            try {
                // All matching is a read-only preflight. Do not begin an operation whose
                // source is already stale, and never silently substitute a newer version.
                var edited=com.aid.previz.workspace.PrevizSourceEdits.prepare(sources,invocation.getUserId(),project,args);
                String current=sources.currentVersion(invocation.getUserId(),project);
                if (!java.util.Objects.equals(args.getString("expectedVersion"),current)) {
                    var actual=current==null?Map.<String,byte[]>of():sources.read(invocation.getUserId(),project,current);
                    if(actual.size()!=edited.files().size() || !edited.files().entrySet().stream().allMatch(item->java.util.Arrays.equals(actual.get(item.getKey()),item.getValue())))
                        throw new com.aid.runtime.tool.RuntimeToolPreparationRejectedException(
                            com.aid.runtime.tool.RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID);
                }
            } catch (IllegalArgumentException invalid) {
                throw new com.aid.runtime.tool.RuntimeToolPreparationRejectedException(
                    com.aid.runtime.tool.RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID,invalid);
            } catch (java.io.IOException unavailable) {
                throw new ServiceException("源码预检读取未完成，请核对文件与存储状态");
            }
        }
        if (isBuild(invocation.getToolCode()))
        {
            String version = args.getString("sourceVersion");
            try {
                if (version == null) throw new ServiceException("PREVIZ_SOURCE_VERSION_REQUIRED");
                var files = sources.read(invocation.getUserId(), project, version);
                String engine = args.getString("engine");
                if (!List.of("threejs", "blender").contains(engine)) throw new ServiceException("PREVIZ_ENGINE_INVALID");
                List<String> entries = files.keySet().stream().filter(path -> "blender".equals(engine)
                        ? path.endsWith(".py") : path.endsWith(".mjs") || path.endsWith(".js")).sorted().toList();
                // A single executable entry is project metadata owned by the host. The model's
                // repeated filename is not execution authority; explicit choice is needed only
                // when the immutable snapshot actually contains multiple candidate entries.
                if (entries.size() == 1) args.put("entry", entries.get(0));
                else if (!entries.contains(args.getString("entry"))) throw new ServiceException("PREVIZ_ENTRY_SELECTION_REQUIRED");
                if(args.containsKey("operation")) {
                    JSONObject manifest=JSON.parseObject(invocation.getContextManifestJson());
                    JSONArray references=manifest==null?null:manifest.getJSONArray("refs");String editorVersion=null;
                    if(references!=null)for(Object raw:references)if(raw instanceof JSONObject ref&&"PREVIZ_EDITOR".equals(ref.getString("kind"))){
                        JSONObject canonical=ref.getJSONObject("canonicalRef");
                        if(canonical==null||!project.equals(canonical.getString("previzProjectId"))||editorVersion!=null)throw new ServiceException("PREVIZ_EDITOR_CONTEXT_INVALID");
                        editorVersion=canonical.getString("objectId");
                    }
                    if(editorVersion==null)throw new ServiceException("PREVIZ_EDITOR_CONTEXT_REQUIRED");
                    args.put("operation",com.aid.previz.workspace.PrevizBuildScopeContract.bind(args.getJSONObject("operation"),editorVersion,editors.read(invocation.getUserId(),project,editorVersion)));
                }
            }
            catch (IllegalArgumentException invalid) {
                throw new RuntimeToolPreparationRejectedException(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_ARGUMENTS_INVALID, invalid);
            }
            catch (java.io.IOException | ServiceException unavailable) {
                throw new RuntimeToolPreparationRejectedException(
                        RuntimeToolPreparationRejectedException.Reason.TOOL_PREPARATION_REFERENCE_CONTEXT_INVALID, unavailable);
            }
        }
        return new RuntimeToolPreparation(args.toJSONString(), null, invocation.getOperationScope());
    }

    @Override public RuntimeToolResult execute(RuntimeToolInvocation invocation)
    {
        String project = project(invocation);
        JSONObject args = JSON.parseObject(invocation.getArgumentsJson());
        try
        {
            JSONObject result = new JSONObject();
            if (TODO.equals(invocation.getToolCode())) {
                result.putAll(com.aid.previz.workspace.PrevizProgressList.validate(args));
            }
            else if (PREVIEW.equals(invocation.getToolCode()) || RENDER_PREVIEW.equals(invocation.getToolCode())) {
                if(previews==null)throw new ServiceException("预览节点尚未配置");
                result=previews.preview(invocation.getUserId(),project,args.getLong("buildStepId"),args.getJSONObject("options")==null?new JSONObject():args.getJSONObject("options"),invocation.getIdempotencyKey());
                if("QUEUED".equals(result.getString("state")))return RuntimeToolResult.waitingResource(result.getLong("queuePosition"));
                if("UNKNOWN".equals(result.getString("state")))return RuntimeToolResult.unknown("PREVIEW_RECEIPT_PENDING","预览回执待核对，不能重复提交");
                if("FAILED".equals(result.getString("state")))return previewFailure(result);
            }
            else if (INSPECT.equals(invocation.getToolCode()))
            {
                String version = sources.currentVersion(invocation.getUserId(), project);
                result.put("sourceVersion", version);
                result.put("paths", version == null ? List.of() : sources.read(invocation.getUserId(), project, version).keySet());
            }
            else if(List.of(LIST_FILES,GLOB_FILES,GREP_FILES).contains(invocation.getToolCode())) {
                String version=args.getString("sourceVersion");if(version==null)version=bound(invocation).getString("sourceVersion");
                if(GREP_FILES.equals(invocation.getToolCode()))result.putAll(com.aid.previz.workspace.PrevizSourceSearch.grep(
                    version==null?Map.of():sources.read(invocation.getUserId(),project,version),args.getString("path"),args.getString("pattern"),args.getString("include"),integerOption(args,"before_context",0),integerOption(args,"after_context",0)));
                else {
                    var files=version==null?List.<PrevizSourceStore.FileInfo>of():sources.inspect(invocation.getUserId(),project,version);
                    result.putAll(LIST_FILES.equals(invocation.getToolCode())?com.aid.previz.workspace.PrevizSourceSearch.list(files,args.getString("path"))
                        :com.aid.previz.workspace.PrevizSourceSearch.glob(files,args.getString("pattern")));
                }
                result.put("sourceVersion",version);
                sourceVersionReceipt(result,invocation.getUserId(),project,version);
            }
            else if (READ.equals(invocation.getToolCode()) || READ_PAGE.equals(invocation.getToolCode()))
            {
                String version = args.getString("sourceVersion");
                if (version == null) version = bound(invocation).getString("sourceVersion");
                if (version == null) throw new ServiceException("工程尚无源码");
                byte[] value = sources.read(invocation.getUserId(), project, version).get(args.getString("path"));
                if (value == null) throw new ServiceException("文件不存在");
                result.put("sourceVersion", version); result.put("path", args.getString("path"));
                sourceVersionReceipt(result,invocation.getUserId(),project,version);
                if(READ_PAGE.equals(invocation.getToolCode())) {
                    result.putAll(com.aid.previz.workspace.PrevizTextPage.read(value,integerOption(args,"offset",1),integerOption(args,"limit",200)));
                } else result.put("content", new String(value, StandardCharsets.UTF_8));
            }
            else if (EDIT.equals(invocation.getToolCode())) {
                var edited=com.aid.previz.workspace.PrevizSourceEdits.prepare(sources,invocation.getUserId(),project,args);
                result.put("sourceVersion",sources.save(invocation.getUserId(),project,args.getString("expectedVersion"),edited.files()));
                result.put("path",args.getString("path"));result.put("replacements",edited.replacements());
            }
            else if (WRITE.equals(invocation.getToolCode()))
            {
                Map<String, byte[]> files = new LinkedHashMap<>();
                JSONObject content = args.getJSONObject("files");
                if (content == null) throw new ServiceException("缺少源码文件");
                content.forEach((name, value) -> { if (!(value instanceof String text)) throw new ServiceException("源码必须为文本"); files.put(name, text.getBytes(StandardCharsets.UTF_8)); });
                result.put("sourceVersion", sources.save(invocation.getUserId(), project, args.getString("expectedVersion"), files));
            }
            else if (isBuild(invocation.getToolCode()))
            {
                JSONObject event = builds.execute(invocation.getUserId(), jobId(invocation.getIdempotencyKey()), project,
                        args.getString("sceneId"), args.getString("sourceVersion"), args.getString("engine"), "plan", args.getString("entry"), args.getBooleanValue("keepBlend"));
                if (!"result".equals(event.getString("type"))) return buildFailure(event);
                result = event.getJSONObject("result");
            }
            else throw new ServiceException("不支持的三维工具");
            result.put("projectId", project);
            RuntimeToolResult completed = RuntimeToolResult.succeeded(JSON.toJSONString(result,com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
            completed.setSafeSummary(switch (invocation.getToolCode()) {
                case INSPECT -> "已读取当前三维工程目录";
                case READ, READ_PAGE, LIST_FILES, GLOB_FILES, GREP_FILES -> "已读取三维工程源码";
                case WRITE, EDIT -> "三维源码版本已保存";
                case BUILD, SCOPED_BUILD -> "三维场景构建已完成，等待载入";
                case PREVIEW, RENDER_PREVIEW -> "单帧预览已生成，等待视觉核对";
                default -> "三维工程操作已完成";
            });
            return completed;
        }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return RuntimeToolResult.unknown("PREVIZ_BUILD_INTERRUPTED", "构建回执待核对"); }
        catch (PrevizSourceStore.SourceVersionConflictException conflict) {
            return readFailure("PREVIZ_SOURCE_VERSION_CONFLICT","工程源码版本已变化，本次未写入；请先读取当前版本再明确修改");
        }
        catch (PrevizSourceStore.SourceQuotaExceededException full) {
            return readFailure("PREVIZ_SOURCE_QUOTA_EXCEEDED","工程源码容量已达上限，本次未写入；请联系管理员调整容量");
        }
        catch (com.aid.compute.ComputeResourcesUnavailableException unavailable) { return resourcesUnavailable(); }
        catch (java.io.IOException invalid) {
            if(readOnly(invocation.getToolCode()))return readFailure("PREVIZ_READ_UNAVAILABLE","读取未完成，请核对文件版本与存储状态");
            if(PREVIEW.equals(invocation.getToolCode())||RENDER_PREVIEW.equals(invocation.getToolCode()))
                return RuntimeToolResult.unknown("PREVIEW_RECEIPT_UNAVAILABLE","预览回执暂不可读取，不能重复提交");
            throw new ServiceException("工程文件操作未完成，请核对源码版本与存储状态");
        }
        catch(IllegalArgumentException|ServiceException invalid){
            if(readOnly(invocation.getToolCode()))return readFailure("PREVIZ_READ_INVALID","读取目标或参数无效，请核对路径与版本");
            throw invalid;
        }
    }
    @Override public RuntimeToolResult reconcilePrepared(RuntimeToolPreparedInvocation prepared)
    {
        RuntimeToolInvocation invocation = new RuntimeToolInvocation();
        invocation.setRunId(prepared.getRunId()); invocation.setUserId(prepared.getUserId());
        invocation.setContextManifestJson(prepared.getContextManifestJson());
        String project = project(invocation);
        JSONObject args = JSON.parseObject(prepared.getPreparedPayloadJson());
        if(readOnly(prepared.getToolCode())){invocation.setToolCode(prepared.getToolCode());invocation.setArgumentsJson(prepared.getPreparedPayloadJson());return execute(invocation);}
        try
        {
            if(PREVIEW.equals(prepared.getToolCode()) || RENDER_PREVIEW.equals(prepared.getToolCode())) {
                if(previews==null)return RuntimeToolResult.unknown("PREVIEW_NODE_UNAVAILABLE","预览节点不可用");
                JSONObject known=previews.lookup(prepared.getUserId(),project,args.getLong("buildStepId"),args.getJSONObject("options")==null?new JSONObject():args.getJSONObject("options"),prepared.getIdempotencyKey());
                if(known!=null&&"QUEUED".equals(known.getString("state")))return RuntimeToolResult.waitingResource(known.getLong("queuePosition"));
                RuntimeToolResult restored=known==null?RuntimeToolResult.unknown("PREVIEW_RECEIPT_PENDING","预览回执待核对")
                    :"FAILED".equals(known.getString("state"))?previewFailure(known):RuntimeToolResult.succeeded(known.toJSONString());
                return RENDER_PREVIEW.equals(prepared.getToolCode()) ? buildArtifacts.archive(prepared,restored) : restored;
            }
            if (isBuild(prepared.getToolCode()))
            {
                requireBuildRuntime(prepared);
                JSONObject event = builds.lookupReceipt(prepared.getUserId(), jobId(prepared.getIdempotencyKey()), project,
                        args.getString("sceneId"), args.getString("sourceVersion"), args.getString("engine"));
                if (event == null) return RuntimeToolResult.unknown("WORKER_RECEIPT_PENDING", "构建回执待核对");
                return "result".equals(event.getString("type"))
                        ? buildArtifacts.archive(prepared, RuntimeToolResult.succeeded(event.getJSONObject("result").toJSONString())) : buildFailure(event);
            }
            if (WRITE.equals(prepared.getToolCode()))
            {
                String current = sources.currentVersion(prepared.getUserId(), project);
                JSONObject expected = args.getJSONObject("files");
                Map<String, byte[]> actual = current == null ? Map.of() : sources.read(prepared.getUserId(), project, current);
                if (expected != null && actual.size() == expected.size() && expected.entrySet().stream().allMatch(item ->
                        item.getValue() instanceof String text && java.util.Arrays.equals(actual.get(item.getKey()), text.getBytes(StandardCharsets.UTF_8))))
                    return RuntimeToolResult.succeeded(JSON.toJSONString(Map.of("projectId", project, "sourceVersion", current)));
            }
            if (EDIT.equals(prepared.getToolCode())) {
                var edited=com.aid.previz.workspace.PrevizSourceEdits.prepare(sources,prepared.getUserId(),project,args);
                if(sources.hasVerifiedVersionConflict(prepared.getUserId(),project,prepared.getIdempotencyKey(),args.getString("expectedVersion"),edited.files()))
                    return readFailure("PREVIZ_SOURCE_VERSION_CONFLICT","经核对，本次操作因源码版本冲突未写入；原场景与已完成结果保留");
                String current=sources.currentVersion(prepared.getUserId(),project);
                var actual=current==null?Map.<String,byte[]>of():sources.read(prepared.getUserId(),project,current);
                if(actual.size()==edited.files().size() && edited.files().entrySet().stream().allMatch(item->java.util.Arrays.equals(actual.get(item.getKey()),item.getValue())))
                    return RuntimeToolResult.succeeded(JSONObject.of("projectId",project,"sourceVersion",current,"path",args.getString("path"),"replacements",edited.replacements()).toJSONString());
            }
            return RuntimeToolResult.unknown("PREVIZ_RECEIPT_PENDING", "操作回执待核对");
        }
        catch (com.aid.compute.ComputeResourcesUnavailableException unavailable) { return resourcesUnavailable(); }
        catch (java.io.IOException unavailable) { return RuntimeToolResult.unknown("PREVIZ_RECEIPT_UNAVAILABLE", "回执暂不可读取"); }
    }
    @Override public void releaseUnsubmitted(RuntimeToolPreparedInvocation prepared) {
        if(!isBuild(prepared.getToolCode())&&!PREVIEW.equals(prepared.getToolCode())&&!RENDER_PREVIEW.equals(prepared.getToolCode()))return;
        RuntimeToolInvocation invocation=new RuntimeToolInvocation();
        invocation.setRunId(prepared.getRunId());invocation.setUserId(prepared.getUserId());
        invocation.setContextManifestJson(prepared.getContextManifestJson());
        // A fenced STOP releases its original admission even if current edit permission was revoked.
        String project=bound(invocation).getString("previzProjectId");JSONObject args=JSON.parseObject(prepared.getPreparedPayloadJson());
        try {
            if(isBuild(prepared.getToolCode()))builds.cancelQueued(prepared.getUserId(),jobId(prepared.getIdempotencyKey()),project);
            else if((PREVIEW.equals(prepared.getToolCode())||RENDER_PREVIEW.equals(prepared.getToolCode()))&&previews!=null)
                previews.cancelQueued(prepared.getUserId(),project,args.getLong("buildStepId"),
                        args.getJSONObject("options")==null?new JSONObject():args.getJSONObject("options"),prepared.getIdempotencyKey());
        } catch(java.io.IOException unavailable){throw new ServiceException("排队资源释放尚未核实",409);}
    }

    private RuntimeToolResult resourcesUnavailable() {
        RuntimeToolResult result=new RuntimeToolResult();result.setStatus("FAILED");
        result.setErrorCode(com.aid.compute.ComputeResourcesUnavailableException.CODE);
        result.setSafeSummary("构建资源维护中，请稍后重试");result.setSubmissionNotEntered(true);result.setRetryable(true);
        return result;
    }

    private RuntimeToolResult buildFailure(JSONObject event)
    {
        if("QUEUED".equals(event.getString("state")))return RuntimeToolResult.waitingResource(event.getLong("queuePosition"));
        if ("UNKNOWN".equals(event.getString("state")) || "WORKER_RECEIPT_PENDING".equals(event.getString("code")))
            return RuntimeToolResult.unknown(event.getString("code"), "构建回执待核对，不能重复提交");
        RuntimeToolResult result = new RuntimeToolResult();
        result.setStatus("FAILED"); result.setErrorCode(event.getString("code"));
        result.setSafeSummary("本地场景构建未完成，请查看执行诊断");
        result.setRetryable(false);
        return result;
    }
    private static boolean readOnly(String code){return code!=null&&List.of(INSPECT,READ,READ_PAGE,LIST_FILES,GLOB_FILES,GREP_FILES,TODO).contains(code);}
    private void sourceVersionReceipt(JSONObject result,Long actor,String project,String version)throws java.io.IOException {
        String current=sources.currentVersion(actor,project);
        result.put("currentSourceVersion",current);
        result.put("isCurrentVersion",java.util.Objects.equals(version,current));
    }
    private static RuntimeToolResult readFailure(String code,String message){var result=new RuntimeToolResult();result.setStatus("FAILED");result.setErrorCode(code);result.setSafeSummary(message);result.setRetryable(false);return result;}
    private RuntimeToolResult previewFailure(JSONObject receipt) {
        RuntimeToolResult result=new RuntimeToolResult();result.setStatus("FAILED");result.setRetryable(false);
        result.setErrorCode("PREVIEW_"+receipt.getString("errorCode"));result.setSafeSummary("预览未完成，已构建模型与手动编辑保持不变");
        result.setOutputJson(receipt.toJSONString());return result;
    }
    private void requireBuildRuntime(RuntimeToolPreparedInvocation prepared) {
        if (prepared.getRuntimeStepId() == null || prepared.getPlanRevision() == null
                || prepared.getRunLease() == null || prepared.getStepLease() == null)
            throw new ServiceException("PREVIZ_BUILD_RUNTIME_REQUIRED");
    }
    private String project(RuntimeToolInvocation invocation)
    {
        if (invocation == null || invocation.getUserId() == null || invocation.getRunId() == null)
            throw new ServiceException("缺少三维工具运行身份");
        String id = bound(invocation).getString("previzProjectId");
        projects.requireWrite(invocation.getUserId(), id);
        return id;
    }
    static JSONObject bound(RuntimeToolInvocation invocation)
    {
        JSONObject manifest = JSON.parseObject(invocation.getContextManifestJson());
        JSONArray refs = manifest == null ? null : manifest.getJSONArray("refs");
        JSONObject found = null;
        if (refs != null) for (Object raw : refs)
        {
            if (raw instanceof JSONObject ref && "PREVIZ_PROJECT".equals(ref.getString("kind")))
            {
                if (found != null || !"WRITE".equals(ref.getString("access"))) throw new ServiceException("必须绑定唯一可编辑三维工程");
                found = ref.getJSONObject("canonicalRef");
            }
        }
        if (found == null) throw new ServiceException("未绑定三维工程");
        return found;
    }
    private String jobId(String idempotencyKey)
    {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new ServiceException("缺少构建幂等标识");
        return "runtime-" + SecureUtil.sha256(idempotencyKey);
    }
    private int integerOption(JSONObject args,String key,int fallback) {
        if(!args.containsKey(key))return fallback;Object number=args.get(key);
        if(!(number instanceof Number)||new java.math.BigDecimal(number.toString()).compareTo(java.math.BigDecimal.valueOf(((Number)number).intValue()))!=0)
            throw new ServiceException("工具数量参数必须为整数");
        return ((Number)number).intValue();
    }
}
