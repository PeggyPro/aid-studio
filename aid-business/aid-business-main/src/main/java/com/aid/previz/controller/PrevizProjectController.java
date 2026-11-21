package com.aid.previz.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.aid.previz.service.PrevizProjectService;
import com.aid.previz.service.PrevizModelService;
import com.aid.previz.service.PrevizArtifactService;
import com.aid.previz.workspace.PrevizSourceStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供独立三维工程及源码版本接口。 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/previz")
@Tag(name = "独立三维工程")
@ConditionalOnProperty(prefix = "aid.previz", name = "enabled", havingValue = "true")
public class PrevizProjectController
{
    private final PrevizProjectService projects;
    private final PrevizSourceStore sources;
    private final PrevizModelService models;
    private final PrevizArtifactService artifacts;
    private final com.aid.previz.service.PrevizReferenceImageStore referenceImages;
    private final com.aid.previz.service.PrevizEditorStore editors;
    private final org.springframework.beans.factory.ObjectProvider<com.aid.previz.service.PrevizPreviewService> previews;

    @Data
    public static class PreviewRequest {
        @NotBlank @Size(max=100) private String projectId;
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive private Long stepId;
        @jakarta.validation.constraints.NotNull private com.alibaba.fastjson2.JSONObject options;
    }
    @PostMapping("/build/preview")
    public AjaxResult preview(@Valid @RequestBody PreviewRequest request) throws IOException,InterruptedException {
        var service=previews.getIfAvailable();if(service==null)throw new ServiceException("预览计算节点尚未配置");
        return AjaxResult.success(service.preview(SecurityUtils.getUserId(),request.getProjectId(),request.getStepId(),request.getOptions()));
    }
    @PostMapping("/reference-image/read")
    public AjaxResult referenceImage(@Valid @RequestBody SourceReadRequest request) throws IOException {
        var image=referenceImages.read(SecurityUtils.getUserId(),request.getProjectId(),request.getVersion());
        return AjaxResult.success(Map.of("contentBase64",java.util.Base64.getEncoder().encodeToString(image.bytes()),"mimeType",image.mimeType(),"sha256",image.id()));
    }
    @PostMapping("/preview/result")
    public AjaxResult previewResult(@Valid @RequestBody ArtifactRequest request) {
        return AjaxResult.success(artifacts.previewReceipt(SecurityUtils.getUserId(),request.getProjectId(),request.getStepId()));
    }

    @Data
    public static class EditorSaveRequest {
        @NotBlank @Size(max=100) private String projectId;
        @Size(max=64) private String expectedVersion;
        @jakarta.validation.constraints.NotNull private com.alibaba.fastjson2.JSONObject state;
    }
    @PostMapping("/editor/current")
    public AjaxResult editorCurrent(@Valid @RequestBody ProjectRequest request) throws IOException {
        var result=new LinkedHashMap<String,Object>();
        result.put("editorVersion",editors.current(SecurityUtils.getUserId(),request.getProjectId()));
        return AjaxResult.success(result);
    }
    @PostMapping("/editor/read")
    public AjaxResult editorRead(@Valid @RequestBody SourceReadRequest request) throws IOException {
        return AjaxResult.success(editors.read(SecurityUtils.getUserId(),request.getProjectId(),request.getVersion()));
    }
    @PostMapping("/editor/save")
    public AjaxResult editorSave(@Valid @RequestBody EditorSaveRequest request) throws IOException {
        try { return AjaxResult.success(Map.of("editorVersion",editors.save(SecurityUtils.getUserId(),request.getProjectId(),request.getExpectedVersion(),request.getState()))); }
        catch(IOException conflict) {
            if(conflict.getMessage()!=null && conflict.getMessage().startsWith("SOURCE_VERSION_CONFLICT:"))
                throw new ServiceException("工程编辑状态已变化，请重新核对后提交",409);
            throw conflict;
        }
    }

    @Data
    public static class ImageSaveRequest {
        @NotBlank @Size(max = 100) private String projectId;
        @NotBlank @Size(max = 6990508) private String contentBase64;
    }

    @PostMapping("/reference-image/save")
    @Operation(summary = "保存独立工程参考图", description = "校验项目权限及图片内容后保存不可变图片，返回内容指纹，不返回磁盘路径")
    public AjaxResult saveReferenceImage(@Valid @RequestBody ImageSaveRequest request) {
        try {
            var image = referenceImages.save(SecurityUtils.getUserId(), request.getProjectId(), request.getContentBase64());
            return AjaxResult.success(Map.of("objectId", image.id(), "contentHash", image.id(),
                    "mimeType", image.mimeType(), "width", image.width(), "height", image.height(), "sizeBytes", image.bytes().length));
        } catch (IOException invalid) { throw new ServiceException("参考图无法保存，请检查格式、尺寸或存储状态"); }
    }

    @Data
    public static class ArtifactRequest
    {
        @NotBlank @Size(max = 100) private String projectId;
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive private Long stepId;
    }

    @PostMapping("/build/artifact")
    @Operation(summary = "读取已确认构建产物", description = "核对项目权限、原任务回执及文件指纹，不接受模型提供的服务器路径")
    public AjaxResult artifact(@Valid @RequestBody ArtifactRequest request)
    {
        try { return AjaxResult.success(artifacts.read(SecurityUtils.getUserId(), request.getProjectId(), request.getStepId())); }
        catch (IOException unavailable) { throw new ServiceException("产物暂不可读取，请稍后重试"); }
    }

    @PostMapping("/build/native-file")
    @Operation(summary = "下载已归档Blender工程", description = "核对项目权限、成功步骤、文件大小及原归档指纹，流式返回原生工程")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> nativeFile(
            @Valid @RequestBody ArtifactRequest request) throws IOException {
        var file = artifacts.nativeFile(SecurityUtils.getUserId(), request.getProjectId(), request.getStepId());
        var input = java.nio.file.Files.newInputStream(file.path(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .header("Content-Disposition", "attachment; filename=scene.blend")
                .header("X-Content-SHA256", file.sha256()).contentLength(file.sizeBytes())
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(new org.springframework.core.io.InputStreamResource(input));
    }

    @PostMapping("/build/manifest")
    @Operation(summary = "读取已归档构建清单", description = "只返回当前项目可访问的已成功步骤元数据，不读取大文件正文")
    public AjaxResult buildManifest(@Valid @RequestBody ArtifactRequest request) {
        return AjaxResult.success(artifacts.manifest(SecurityUtils.getUserId(), request.getProjectId(), request.getStepId()));
    }

    @Data
    public static class CreateRequest
    {
        @NotBlank
        @Size(max = 120)
        private String title;
        @NotBlank
        @Size(max = 100)
        private String clientRequestId;
    }

    @Data
    public static class ProjectRequest
    {
        @NotBlank
        @Size(max = 100)
        private String projectId;
    }

    @Data
    public static class SourceSaveRequest
    {
        @NotBlank
        @Size(max = 100)
        private String projectId;
        private String expectedVersion;
        @NotEmpty
        @Size(max = 125)
        private Map<String, String> files;
    }

    @Data
    public static class SourceReadRequest
    {
        @NotBlank
        @Size(max = 100)
        private String projectId;
        @NotBlank
        @Size(min = 64, max = 64)
        private String version;
    }

    @PostMapping("/project/create")
    @Operation(summary = "创建独立三维工程", description = "当前登录用户为所有者，不创建自由画布工作区")
    public AjaxResult create(@Valid @RequestBody CreateRequest request)
    {
        return AjaxResult.success(projects.create(SecurityUtils.getUserId(), request.getTitle(), request.getClientRequestId()));
    }

    @PostMapping("/project/detail")
    @Operation(summary = "读取独立三维工程", description = "校验当前项目成员权限后返回工程元数据")
    public AjaxResult detail(@Valid @RequestBody ProjectRequest request)
    {
        return AjaxResult.success(projects.detail(SecurityUtils.getUserId(), request.getProjectId()));
    }

    @PostMapping("/model/catalog")
    @Operation(summary = "读取新建三维项目可用文本模型", description = "复用固定Agent版本的模型就绪校验；只读，不创建项目或调用模型")
    public AjaxResult modelCatalog()
    {
        return AjaxResult.success(models.catalog(SecurityUtils.getUserId()));
    }

    @PostMapping("/model/options")
    @Operation(summary = "读取工程文本模型选项", description = "复用当前空间Agent固定版本及模型协议校验，不返回供应商凭证")
    public AjaxResult models(@Valid @RequestBody ProjectRequest request)
    {
        return AjaxResult.success(models.options(SecurityUtils.getUserId(), request.getProjectId()));
    }

    @PostMapping("/source/save")
    @Operation(summary = "保存源码版本", description = "以预期版本作并发校验，UTF-8源码写入私有磁盘，不将文件正文写入数据库")
    public AjaxResult save(@Valid @RequestBody SourceSaveRequest request)
    {
        Map<String, byte[]> files = new LinkedHashMap<>();
        request.getFiles().forEach((name, content) -> {
            if (content == null) throw new ServiceException("源码内容不能为空值");
            files.put(name, content.getBytes(StandardCharsets.UTF_8));
        });
        try
        {
            String version = sources.save(SecurityUtils.getUserId(), request.getProjectId(), request.getExpectedVersion(), files);
            return AjaxResult.success(Map.of("sourceVersionId", version));
        }
        catch (IOException | IllegalArgumentException exception)
        {
            log.error("保存三维源码失败，projectId={}", request.getProjectId(), exception);
            if (exception instanceof PrevizSourceStore.SourceQuotaExceededException)
                throw new ServiceException("工程源码容量已达上限，请联系管理员调整；原版本已保留", 429);
            if (exception.getMessage() != null && exception.getMessage().startsWith("SOURCE_VERSION_CONFLICT:"))
                throw new ServiceException("工程已更新，请读取最新版本后再保存", 409);
            throw new ServiceException("源码保存失败，请检查路径、文件限额和存储状态");
        }
    }

    @PostMapping("/source/read")
    @Operation(summary = "读取固定源码版本", description = "校验项目权限和内容指纹后返回文本文件，不返回服务器路径")
    public AjaxResult read(@Valid @RequestBody SourceReadRequest request)
    {
        try
        {
            Map<String, String> files = new LinkedHashMap<>();
            sources.read(SecurityUtils.getUserId(), request.getProjectId(), request.getVersion())
                    .forEach((name, content) -> files.put(name, new String(content, StandardCharsets.UTF_8)));
            return AjaxResult.success(Map.of("sourceVersionId", request.getVersion(), "files", files));
        }
        catch (IOException | IllegalArgumentException exception)
        {
            log.error("读取三维源码失败，projectId={}", request.getProjectId(), exception);
            throw new ServiceException("源码版本不可读取，请检查版本或存储状态");
        }
    }

    @PostMapping("/source/current")
    @Operation(summary = "读取当前源码版本", description = "校验项目权限；尚未保存源码时sourceVersionId为null")
    public AjaxResult current(@Valid @RequestBody ProjectRequest request)
    {
        try
        {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sourceVersionId", sources.currentVersion(SecurityUtils.getUserId(), request.getProjectId()));
            return AjaxResult.success(result);
        }
        catch (IOException | IllegalArgumentException exception)
        {
            log.error("读取三维源码指针失败，projectId={}", request.getProjectId(), exception);
            throw new ServiceException("当前源码版本不可读取，请检查存储状态");
        }
    }
}
