package com.aid.previz.service;

import com.aid.common.exception.ServiceException;
import com.aid.previz.workspace.PrevizSourceStore;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 本地开发计算适配器。由已授权Runtime步骤调用，不提供绕过任务审批的HTTP接口。 */
@Service
@ConditionalOnProperty(prefix = "aid.previz.local-worker", name = "enabled", havingValue = "true")
public class PrevizLocalBuildService
{
    private final PrevizProjectService projects;
    private final PrevizSourceStore sources;
    private final Path node;
    private final Path entry;
    private final Path profile;
    private final Path requests;
    private final PrevizComputeDispatchService dispatch;

    public record NativeFile(Path path, long sizeBytes, String sha256) { }

    /** Reads only the fixed native artifact recorded by a completed build; no caller-selected paths. */
    public NativeFile nativeArtifact(Long actorId, String projectId, String jobId) throws IOException {
        projects.requireRead(actorId, projectId);
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,100}")) throw new ServiceException("构建标识无效");
        JSONObject request = readJson(requests.resolve(jobId + ".json"), 32768);
        JSONObject event = lookupReceipt(actorId, jobId, projectId, request.getString("sceneId"), request.getString("sourceVersionId"), request.getString("engine"));
        if (event == null || !"result".equals(event.getString("type")) || !"blender".equals(request.getString("engine")))
            throw new ServiceException("原生工程尚未就绪");
        var files = event.getJSONObject("result").getJSONArray("nativeArtifacts");
        if (files == null || files.size() != 1 || !"scene.blend".equals(files.getJSONObject(0).getString("name")))
            throw new ServiceException("此构建没有归档原生工程");
        JSONObject metadata = files.getJSONObject(0);
        Path root = Path.of(readJson(profile, 16384).getString("executionRoot")).toRealPath();
        Path directory = Path.of(event.getString("outputDirectory"));
        Path file = directory.resolve("scene.blend");
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file) || !Files.isRegularFile(file)
                || !file.toRealPath().startsWith(root)) throw new ServiceException("原生工程位置无效");
        long size = Files.size(file);
        if (size < 1 || size > 128L * 1024 * 1024 || size != metadata.getLongValue("sizeBytes"))
            throw new ServiceException("原生工程大小校验失败");
        String digest;
        try (var input = Files.newInputStream(file)) { digest = cn.hutool.crypto.SecureUtil.sha256(input); }
        if (!digest.equals(metadata.getString("sha256"))) throw new ServiceException("原生工程指纹校验失败");
        projects.requireRead(actorId, projectId);
        return new NativeFile(file, size, digest);
    }

    /** 只查原任务回执；没有回执不能据此再次提交。 */
    public JSONObject lookupReceipt(Long actorId, String jobId, String projectId, String sceneId,
            String sourceVersion, String engine) throws IOException
    {
        projects.requireRead(actorId, projectId);
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,100}")) throw new ServiceException("构建任务标识无效");
        Path requestFile = requests.resolve(jobId + ".json");
        if (!Files.exists(requestFile)) return null;
        JSONObject request = readJson(requestFile, 32768);
        if (!java.util.Objects.equals(projectId, request.getString("projectId"))
                || !java.util.Objects.equals(sceneId, request.getString("sceneId"))
                || !java.util.Objects.equals(sourceVersion, request.getString("sourceVersionId"))
                || !java.util.Objects.equals(engine, request.getString("engine")))
            throw new ServiceException("构建任务与当前项目或版本不一致");
        try(var route=dispatch.route(jobId,projectId,engine,"build",null,false)) {
        if(route.waiting())return queued(route);
        if(route.managed()&&dispatch.binding(jobId).getWorkerJobId()==null)return queued(route);
        JSONObject config = readJson(route.profile(), 16384);
        Path executionRoot = Path.of(config.getString("executionRoot"));
        if (!executionRoot.isAbsolute()) throw new ServiceException("本地执行目录配置无效");
        Path receipt = executionRoot.resolve(".receipts").resolve(jobId).resolve("result.json");
        if (!Files.exists(receipt) && "REMOTE_COMPUTE".equals(config.getString("mode")))
        {
            // This operation can only query/download the original job, never submit it again.
            Path lookupLog = Files.createTempFile(requests, jobId + "-lookup-", ".log");
            ProcessBuilder lookup = new ProcessBuilder(node.toString(), route.managed()?entry.resolveSibling("remote-cli.mjs").toString():entry.toString(), route.profile().toString(),
                    requestFile.toString(), "--lookup-only");
            lookup.directory(requests.toFile());
            prepareEnvironment(lookup);
            lookup.redirectErrorStream(true).redirectOutput(lookupLog.toFile());
            Process process = lookup.start();
            try { if (!process.waitFor(35, TimeUnit.SECONDS)) stop(process); }
            catch (InterruptedException interrupted) { stop(process); Thread.currentThread().interrupt(); }
        }
        if (!Files.exists(receipt))
        {
            dispatch.reconcile(route);
            JSONObject pending = new JSONObject();
            pending.put("type", "error"); pending.put("state", "UNKNOWN");
            pending.put("code", "WORKER_RECEIPT_PENDING");
            return pending;
        }
        JSONObject event = readJson(receipt, 1024 * 1024).getJSONObject("event");
        if (event == null) throw new ServiceException("本地任务回执无效");
        if ("result".equals(event.getString("type")))
        {
            JSONObject result = event.getJSONObject("result");
            if (result == null || !jobId.equals(result.getString("jobId"))
                    || !java.util.Objects.equals(projectId, result.getString("projectId"))
                    || !java.util.Objects.equals(sceneId, result.getString("sceneId"))
                    || !java.util.Objects.equals(sourceVersion, result.getString("sourceSha256"))
                    || !java.util.Objects.equals(engine, result.getString("engine")))
                throw new ServiceException("本地任务回执身份不一致");
        }
        dispatch.reconcile(route);
        return event;
        }
    }

    private static JSONObject readJson(Path file, long limit) throws IOException
    {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > limit)
            throw new ServiceException("本地任务文件无效");
        try { return JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8)); }
        catch (RuntimeException invalid) { throw new ServiceException("本地任务文件不可读取"); }
    }

    /** Return verified bytes to the authenticated project endpoint; never expose local paths. */
    public JSONObject artifact(Long actorId, String projectId, String jobId) throws IOException
    {
        projects.requireRead(actorId, projectId);
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,100}")) throw new ServiceException("构建标识无效");
        JSONObject request = readJson(requests.resolve(jobId + ".json"), 32768);
        JSONObject event = lookupReceipt(actorId, jobId, projectId, request.getString("sceneId"),
                request.getString("sourceVersionId"), request.getString("engine"));
        if (event == null || !"result".equals(event.getString("type"))) throw new ServiceException("构建产物尚未就绪");
        Path root = Path.of(readJson(profile, 16384).getString("executionRoot")).toRealPath();
        Path directory = Path.of(event.getString("outputDirectory"));
        if (Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(root)) throw new ServiceException("构建产物目录无效");
        Path file = directory.resolve("scene.glb");
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(root)
                || Files.size(file) > 32L * 1024 * 1024) throw new ServiceException("构建产物无效或超出限额");
        byte[] data = Files.readAllBytes(file);
        JSONObject result = event.getJSONObject("result");
        String digest = cn.hutool.crypto.SecureUtil.sha256(new java.io.ByteArrayInputStream(data));
        if (result.getJSONObject("artifact") == null || !digest.equals(result.getJSONObject("artifact").getString("sha256")))
            throw new ServiceException("构建产物指纹不一致");
        projects.requireRead(actorId, projectId);
        JSONObject response = JSON.parseObject(result.toJSONString());
        response.put("contentBase64", java.util.Base64.getEncoder().encodeToString(data));
        response.put("mimeType", "model/gltf-binary");
        return response;
    }

    public PrevizLocalBuildService(PrevizProjectService projects, PrevizSourceStore sources,
            @Value("${aid.previz.local-worker.node}") String node,
            @Value("${aid.previz.local-worker.entry}") String entry,
            @Value("${aid.previz.local-worker.profile}") String profile,
            @Value("${aid.previz.local-worker.requests-root}") String requests,PrevizComputeDispatchService dispatch) throws IOException
    {
        this.projects = projects;
        this.sources = sources;
        this.dispatch = dispatch;
        this.node = existingFile(node);
        this.entry = existingFile(entry);
        this.profile = existingFile(profile);
        Path requestRoot = Path.of(requests);
        if (!requestRoot.isAbsolute()) throw new IllegalArgumentException("本地请求目录必须为绝对路径");
        Files.createDirectories(requestRoot);
        if (Files.isSymbolicLink(requestRoot)) throw new IllegalArgumentException("本地请求目录不能是链接");
        this.requests = requestRoot.toRealPath();
    }

    /** jobId由Runtime幂等标识派生，模型不能提供用户身份、执行路径或执行开关。 */
    public JSONObject execute(Long actorId, String jobId, String projectId, String sceneId,
            String sourceVersion, String engine, String engineOrigin, String sourceEntry, boolean keepBlend) throws IOException, InterruptedException
    {
        projects.requireWrite(actorId, projectId);
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,100}")
                || sceneId == null || !sceneId.matches("[A-Za-z0-9_-]{1,100}"))
            throw new ServiceException("构建任务或场景标识无效");
        if (!"threejs".equals(engine) && !"blender".equals(engine))
            throw new ServiceException("场景引擎无效");
        if (!java.util.Set.of("user", "project", "default", "plan").contains(engineOrigin == null ? "" : engineOrigin))
            throw new ServiceException("缺少引擎选择依据");
        Map<String, byte[]> snapshot = sources.read(actorId, projectId, sourceVersion);
        if (!snapshot.containsKey(sourceEntry)) throw new ServiceException("源码入口不存在");
        JSONObject request = new JSONObject();
        request.put("protocol", "aid.scene-build/1");
        request.put("jobId", jobId);
        request.put("projectId", projectId);
        request.put("sceneId", sceneId);
        request.put("sourceVersionId", sourceVersion);
        request.put("sourceSha256", sourceVersion);
        request.put("engine", engine);
        request.put("entry", sourceEntry);
        request.put("engineSelection", Map.of("origin", engineOrigin));
        request.put("keepBlend", keepBlend);
        Path requestFile = requests.resolve(jobId + ".json");
        byte[] bytes = JSON.toJSONBytes(request);
        try { Files.write(requestFile, bytes, StandardOpenOption.CREATE_NEW); }
        catch (java.nio.file.FileAlreadyExistsException existing)
        {
            if (Files.isSymbolicLink(requestFile) || !java.util.Arrays.equals(bytes, Files.readAllBytes(requestFile)))
                throw new ServiceException("同一构建任务不能变更参数");
        }
        try(var route=dispatch.route(jobId,projectId,engine,"build",null,true)) {
        if(route.waiting())return queued(route);
        Path output = Files.createTempFile(requests, jobId + "-", ".log");
        ProcessBuilder builder = new ProcessBuilder(node.toString(), route.managed()?entry.resolveSibling("remote-cli.mjs").toString():entry.toString(), route.profile().toString(), requestFile.toString());
        builder.directory(requests.toFile());
        prepareEnvironment(builder);
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        dispatch.beforeSubmission(route);
        Process process = builder.start();
        try
        {
            if (!process.waitFor(150, TimeUnit.SECONDS))
            {
                stop(process);
                throw new ServiceException("本地计算回执未确认，请核对原任务，不能直接重复提交");
            }
        }
        catch (InterruptedException interrupted)
        {
            stop(process);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (Files.size(output) > 1024 * 1024) throw new ServiceException("计算回执超出限额");
        String text = Files.readString(output, StandardCharsets.UTF_8).strip();
        String last = text.substring(text.lastIndexOf('\n') + 1);
        JSONObject event;
        try { event = JSON.parseObject(last); }
        catch (RuntimeException invalid) { throw new ServiceException("本地计算回执不可读取，请核对原任务"); }
        if (event == null) throw new ServiceException("本地计算回执为空");
        if ("result".equals(event.getString("type")))
        {
            JSONObject result = event.getJSONObject("result");
            if (process.exitValue() != 0 || result == null || !jobId.equals(result.getString("jobId"))
                    || !projectId.equals(result.getString("projectId")) || !sceneId.equals(result.getString("sceneId"))
                    || !sourceVersion.equals(result.getString("sourceSha256")) || !engine.equals(result.getString("engine")))
                throw new ServiceException("本地计算回执与当前任务不一致");
        }
        // Recheck after a long build; returning a receipt never bypasses revoked access.
        projects.requireWrite(actorId, projectId);
        dispatch.reconcile(route);
        return event;
        }
    }

    private static JSONObject queued(PrevizComputeDispatchService.Route route){
        return JSONObject.of("type","deferred","state","QUEUED","code","COMPUTE_CAPACITY_WAIT",
                "queuePosition",route.queuePosition(),"message",route.waitingMessage());
    }

    public void cancelQueued(Long actor,String job,String project)throws IOException{
        if(actor==null||actor<=0)throw new ServiceException("缺少排队释放身份",409);
        com.aid.compute.ComputeResourcePolicy.key(job);
        Path file=requests.resolve(job+".json");
        if(Files.exists(file)&&!project.equals(readJson(file,32768).getString("projectId")))
            throw new ServiceException("构建排队任务与项目不匹配",409);
        dispatch.cancelUnsubmitted(job,project);
    }

    private static Path existingFile(String value) throws IOException
    {
        Path file = Path.of(value);
        if (!file.isAbsolute() || Files.isSymbolicLink(file) || !Files.isRegularFile(file))
            throw new IllegalArgumentException("本地执行配置必须指向存在的普通文件");
        return file.toRealPath();
    }

    private void prepareEnvironment(ProcessBuilder builder)
    {
        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(key -> !java.util.Set.of("PATH", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC").contains(key.toUpperCase(java.util.Locale.ROOT)));
        environment.put("TEMP", requests.toString());
        environment.put("TMP", requests.toString());
    }

    private static void stop(Process process)
    {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
    }
}
