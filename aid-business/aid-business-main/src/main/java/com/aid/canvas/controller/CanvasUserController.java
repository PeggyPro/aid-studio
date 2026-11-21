package com.aid.canvas.controller;

import com.aid.aid.domain.dto.OssUploadResponse;
import com.aid.canvas.dto.CanvasRequests;
import com.aid.canvas.service.CanvasAccessService;
import com.aid.canvas.service.CanvasCollaborationService;
import com.aid.canvas.service.CanvasGenerationService;
import com.aid.canvas.service.CanvasImageSplitService;
import com.aid.canvas.service.CanvasImageLayerMergeService;
import com.aid.canvas.service.CanvasProjectService;
import com.aid.canvas.service.CanvasResourceService;
import com.aid.canvas.service.CanvasTextImportService;
import com.aid.canvas.service.CanvasVideoSubjectDetectionService;
import com.aid.generation.service.GenerationConversationStore;
import com.aid.aid.service.IOssBusinessService;
import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/user/canvas")
public class CanvasUserController extends BaseController {
    @Resource private CanvasProjectService projectService;
    @Resource private com.aid.canvas.publication.runtime.CanvasPublicationStateQuery publicationStateQuery;
    @Resource private CanvasCollaborationService collaborationService;
    @Resource private CanvasAccessService accessService;
    @Resource private CanvasGenerationService generationService;
    @Resource private CanvasTextImportService textImportService;
    @Resource private GenerationConversationStore conversationStore;
    @Resource private CanvasResourceService resourceService;
    @Resource private CanvasImageSplitService imageSplitService;
    @Resource private CanvasImageLayerMergeService imageLayerMergeService;
    @Resource private CanvasVideoSubjectDetectionService videoSubjectDetectionService;
    @Resource private IOssBusinessService ossBusinessService;
    @Resource private RedissonClient redissonClient;

    @PostMapping("/project/create")
    public AjaxResult createProject(@Valid @RequestBody CanvasRequests.CreateProject request) {
        return success(projectService.createProject(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/project/list")
    public AjaxResult projectList(@Valid @RequestBody(required = false) CanvasRequests.ProjectList request) {
        Map<String,Object> result=projectService.listProjects(request == null ? new CanvasRequests.ProjectList() : request, SecurityUtils.getUserId());
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)result.get("items");
        publicationStateQuery.enrich(items);
        return success(result);
    }

    @PostMapping("/project/import")
    public AjaxResult importProject(@Valid @RequestBody CanvasRequests.ImportProject request) {
        return success(projectService.importProject(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/project/detail")
    public AjaxResult projectDetail(@Valid @RequestBody CanvasRequests.ProjectId request) {
        Map<String,Object> result=projectService.projectDetail(request.getProjectId(), SecurityUtils.getUserId());
        publicationStateQuery.enrich(List.of(result));
        return success(result);
    }

    @PostMapping("/project/update")
    public AjaxResult updateProject(@Valid @RequestBody CanvasRequests.UpdateProject request) {
        return success(projectService.updateProject(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/project/delete")
    public AjaxResult deleteProject(@Valid @RequestBody CanvasRequests.ProjectId request) {
        projectService.deleteProject(request.getProjectId(), SecurityUtils.getUserId());
        return success();
    }

    @PostMapping("/workspace/load")
    public AjaxResult load(@Valid @RequestBody CanvasRequests.LoadWorkspace request) {
        return success(projectService.loadWorkspace(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/workspace/tasks/active")
    @Operation(summary = "查询画布进行中节点任务", description = "一次返回工作区全部未终态节点任务，只读取本地任务状态。")
    public AjaxResult activeTasks(@Valid @RequestBody CanvasRequests.ActiveTasks request) {
        return success(generationService.activeTasks(request.getWorkspaceId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/workspace/mutate")
    public AjaxResult mutate(@Valid @RequestBody CanvasRequests.Mutate request) {
        return success(projectService.mutate(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/workspace/copy")
    public AjaxResult copy(@Valid @RequestBody CanvasRequests.CopyObjects request) {
        return success(projectService.copyObjects(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/workspace/undo")
    public AjaxResult undo(@Valid @RequestBody CanvasRequests.UndoOperation request) {
        return success(projectService.undo(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/workspace/changes")
    public AjaxResult changes(@Valid @RequestBody CanvasRequests.Changes request) {
        return success(projectService.changes(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/workspace/view/load")
    public AjaxResult loadView(@Valid @RequestBody CanvasRequests.WorkspaceId request) {
        return success(projectService.loadView(request.getWorkspaceId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/workspace/view/save")
    public AjaxResult saveView(@Valid @RequestBody CanvasRequests.SaveView request) {
        return success(projectService.saveView(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/workspace/draft/list")
    public AjaxResult drafts(@Valid @RequestBody CanvasRequests.WorkspaceId request) {
        return success(projectService.loadDrafts(request.getWorkspaceId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/workspace/draft/save")
    public AjaxResult saveDraft(@Valid @RequestBody CanvasRequests.SaveDraft request) {
        return success(projectService.saveDraft(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/workspace/draft/delete")
    public AjaxResult deleteDraft(@Valid @RequestBody CanvasRequests.DeleteDraft request) {
        projectService.deleteDraft(request, SecurityUtils.getUserId());
        return success();
    }

    @PostMapping("/project/setting/list")
    public AjaxResult projectSettings(@Valid @RequestBody CanvasRequests.ProjectId request) {
        return success(projectService.projectSettings(request.getProjectId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/project/setting/update")
    public AjaxResult updateProjectSetting(@Valid @RequestBody CanvasRequests.ProjectSettingUpdate request) {
        return success(projectService.updateProjectSetting(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/member/list")
    public AjaxResult members(@Valid @RequestBody CanvasRequests.ProjectId request) {
        return success(projectService.members(request.getProjectId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/member/role")
    public AjaxResult memberRole(@Valid @RequestBody CanvasRequests.MemberRole request) {
        projectService.updateMemberRole(request, SecurityUtils.getUserId());
        return success();
    }

    @PostMapping("/member/remove")
    public AjaxResult removeMember(@Valid @RequestBody CanvasRequests.MemberUser request) {
        projectService.removeMember(request, SecurityUtils.getUserId());
        return success();
    }

    @PostMapping("/member/exit")
    public AjaxResult exit(@Valid @RequestBody CanvasRequests.ProjectId request) {
        projectService.exitProject(request.getProjectId(), SecurityUtils.getUserId());
        return success();
    }

    @PostMapping("/invite/create")
    public AjaxResult createInvite(@Valid @RequestBody CanvasRequests.CreateInvite request) {
        return success(projectService.createInvite(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/invite/list")
    public AjaxResult invites(@Valid @RequestBody CanvasRequests.ProjectId request) {
        return success(projectService.invites(request.getProjectId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/invite/revoke")
    public AjaxResult revokeInvite(@Valid @RequestBody CanvasRequests.ProjectEntityId request) {
        projectService.revokeInvite(request.getProjectId(), request.getId(), SecurityUtils.getUserId());
        return success();
    }

    @PostMapping("/application/apply")
    public AjaxResult apply(@Valid @RequestBody CanvasRequests.ApplyInvite request) {
        return success(projectService.applyInvite(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/application/list")
    public AjaxResult applications(@Valid @RequestBody CanvasRequests.ProjectId request) {
        return success(projectService.applications(request.getProjectId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/application/review")
    public AjaxResult review(@Valid @RequestBody CanvasRequests.ReviewApplication request) {
        projectService.reviewApplication(request, SecurityUtils.getUserId());
        return success();
    }

    @PostMapping("/share/create")
    public AjaxResult createShare(@Valid @RequestBody CanvasRequests.CreateShare request) {
        return success(projectService.createShare(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/share/detail")
    public AjaxResult shareDetail(@Valid @RequestBody CanvasRequests.ShareToken request) {
        return success(projectService.shareDetail(request.getShareToken(), SecurityUtils.getUserId()));
    }

    @PostMapping("/share/list")
    public AjaxResult shares(@Valid @RequestBody CanvasRequests.ProjectId request) {
        return success(projectService.shares(request.getProjectId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/share/update")
    public AjaxResult updateShare(@Valid @RequestBody CanvasRequests.ProjectEntityId request) {
        return success(projectService.updateShare(request.getProjectId(), request.getId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/share/revoke")
    public AjaxResult revokeShare(@Valid @RequestBody CanvasRequests.ProjectEntityId request) {
        projectService.revokeShare(request.getProjectId(), request.getId(), SecurityUtils.getUserId());
        return success();
    }

    @PostMapping("/entitlement/effective")
    public AjaxResult effective(@Valid @RequestBody(required = false) CanvasRequests.EffectiveEntitlement request) {
        return success(accessService.effectiveEntitlements(SecurityUtils.getUserId(), request == null ? null : request.getProjectId()));
    }

    @PostMapping("/resource/register")
    public AjaxResult registerResource(@Valid @RequestBody CanvasRequests.ResourceRegister request) {
        return success(resourceService.register(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/resource/metadata/prepare")
    @Operation(summary = "准备画布视频素材信息", description = "仅解析并保存已登记本站视频的可信元数据，不创建生成任务或扣除积分。")
    public AjaxResult prepareResourceMetadata(@Valid @RequestBody CanvasRequests.ResourceMetadataPrepare request) {
        Long userId = SecurityUtils.getUserId();
        accessService.requireWorkspace(request.getWorkspaceId(), userId, "OWNER", "EDITOR");
        RLock lock = redissonClient.getLock("canvas:resource:metadata:" + request.getWorkspaceId() + ":" + request.getResourceId());
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, TimeUnit.SECONDS);
            if (!acquired) {
                com.aid.common.core.domain.ApiErrorDetails details = new com.aid.common.core.domain.ApiErrorDetails();
                details.setErrorCode("CANVAS_RESOURCE_METADATA_PREPARATION_BUSY");
                details.setRetryable(false);
                throw new com.aid.common.exception.ServiceException("媒体信息正在准备，请稍后重试", 409)
                        .setClientErrorData(details);
            }
            return success(resourceService.prepareMetadata(request, userId));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new com.aid.common.exception.ServiceException("媒体信息准备被中断");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    @PostMapping("/image/split")
    @Operation(summary = "按原图像素裁切画布图片", description = "生成并登记图片素材，不发起模型任务或扣除生成积分。")
    public AjaxResult splitImage(@Valid @RequestBody CanvasRequests.ImageSplit request) {
        return success(imageSplitService.split(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/image/layer/merge")
    @Operation(summary = "合并画布已登记图片图层",
            description = "确定性生成并登记透明 PNG，不创建媒体任务且不扣除积分。")
    public AjaxResult mergeImageLayers(@Valid @RequestBody CanvasRequests.ImageLayerMerge request) {
        return success(imageLayerMergeService.merge(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/text/import")
    @CryptoIgnore
    public AjaxResult importText(@RequestParam("workspaceId") Long workspaceId,
                                 @RequestParam("file") MultipartFile file) {
        return success(textImportService.parse(workspaceId, file, SecurityUtils.getUserId()));
    }

    @PostMapping("/resource/transfer")
    public AjaxResult transferResource(@Valid @RequestBody CanvasRequests.ResourceTransfer request) {
        return success(resourceService.transfer(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/resource/upload")
    @CryptoIgnore
    public AjaxResult uploadResource(@RequestParam("workspaceId") Long workspaceId,
                                     @RequestParam("mediaTypes") List<String> mediaTypes,
                                     @RequestParam("files") List<MultipartFile> files,
                                     @RequestParam(value = "uploadRequestId", required = false) String uploadRequestId) {
        Long userId = SecurityUtils.getUserId();
        accessService.requireWorkspace(workspaceId, userId, "OWNER", "EDITOR");
        validateUploadRequest(files, mediaTypes);
        String requestHash = uploadHash(files, mediaTypes);
        String requestId = uploadRequestId == null ? "content_" + requestHash.substring(0, 32) : uploadRequestId;
        if (!requestId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new com.aid.common.exception.ServiceException("上传请求标识格式错误");
        }
        RLock lock = redissonClient.getLock("canvas:upload:" + workspaceId + ":" + userId + ":" + requestId);
        try {
            if (!lock.tryLock(10, TimeUnit.SECONDS)) {
                throw new com.aid.common.exception.ServiceException("相同上传请求正在处理，请稍后重试");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new com.aid.common.exception.ServiceException("上传等待被中断");
        }
        try {
            List<java.util.Map<String, Object>> recovered = resourceService.recoverUploads(
                    workspaceId, requestId, requestHash, files.size(), userId);
            if (recovered != null) return success(recovered);
            List<CanvasResourceService.UploadMetadata> metadata = resourceService.inspectUploads(mediaTypes, files);
            List<OssUploadResponse> uploads = ossBusinessService.uploadList(files, "canvas/" + userId);
            try {
                return success(resourceService.registerUploads(workspaceId, uploads, metadata,
                        requestId, requestHash, userId));
            } catch (RuntimeException error) {
                ossBusinessService.deleteListByUrl(uploads.stream().map(OssUploadResponse::getUrl).toList());
                throw error;
            }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private static String uploadHash(List<MultipartFile> files, List<String> mediaTypes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int index = 0; index < files.size(); index++) {
                MultipartFile file = files.get(index);
                String header = index + ":" + mediaTypes.get(index).toUpperCase(Locale.ROOT) + ":"
                        + file.getSize() + ":" + file.getOriginalFilename() + "\n";
                digest.update(header.getBytes(StandardCharsets.UTF_8));
                try (InputStream input = file.getInputStream()) {
                    int read;
                    while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) {
            throw new com.aid.common.exception.ServiceException("上传文件无法计算校验摘要");
        }
    }

    @PostMapping("/collaboration/ticket")
    public AjaxResult ticket(@Valid @RequestBody CanvasRequests.Ticket request) {
        return success(collaborationService.createTicket(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/collaboration/state")
    public AjaxResult collaborationState(@Valid @RequestBody CanvasRequests.WorkspaceId request) {
        return success(collaborationService.state(request.getWorkspaceId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/collaboration/lock/acquire")
    public AjaxResult acquire(@Valid @RequestBody CanvasRequests.LockAcquire request) {
        return success(java.util.Map.of("locks", collaborationService.acquire(request, SecurityUtils.getUserId())));
    }

    @PostMapping("/collaboration/lock/renew")
    public AjaxResult renew(@Valid @RequestBody CanvasRequests.LockChange request) {
        return success(java.util.Map.of("locks", collaborationService.renew(request, SecurityUtils.getUserId())));
    }

    @PostMapping("/collaboration/lock/release")
    public AjaxResult release(@Valid @RequestBody CanvasRequests.LockChange request) {
        collaborationService.release(request, SecurityUtils.getUserId());
        return success();
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "登记音频参考附件", description = "只使用当前工作区已授权素材；复用官方上传与归属记录，不创建生成任务或扣费")
    @PostMapping("/generate/audio/attachment")
    public AjaxResult prepareAudioAttachment(@Valid @RequestBody CanvasRequests.AudioAttachment request) {
        return success(generationService.prepareAudioAttachment(request, SecurityUtils.getUserId()));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "音频节点生成", description = "复用业务能力绑定、节点租约和统一媒体任务；参数由当前音频能力决定")
    @PostMapping("/generate/audio")
    public AjaxResult generateAudio(@Valid @RequestBody CanvasRequests.GenerateAudio request) {
        return success(generationService.generateAudio(request, SecurityUtils.getUserId()));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "音频节点报价", description = "只读验证当前业务能力、工作区素材和计价参数，不创建任务或冻结积分")
    @PostMapping("/generate/audio/quote")
    public AjaxResult quoteAudio(@Valid @RequestBody CanvasRequests.AudioQuote request) {
        return success(generationService.quoteAudio(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/generate/image")
    public AjaxResult generateImage(@Valid @RequestBody CanvasRequests.GenerateImage request) {
        return success(generationService.generateImage(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/generate/video")
    public AjaxResult generateVideo(@Valid @RequestBody CanvasRequests.GenerateVideo request) {
        return success(generationService.generateVideo(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/generate/video/quote")
    public AjaxResult quoteVideoTool(@Valid @RequestBody CanvasRequests.VideoToolQuote request) {
        return success(generationService.quoteVideoTool(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/video/subject/detect")
    public AjaxResult detectVideoSubject(@Valid @RequestBody CanvasRequests.VideoSubjectDetect request) {
        return success(videoSubjectDetectionService.detect(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/generate/task")
    public AjaxResult task(@Valid @RequestBody CanvasTaskRequest request) {
        return success(generationService.taskDetail(request.getWorkspaceId(), request.getTaskId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/generate/history")
    public AjaxResult taskHistory(@Valid @RequestBody CanvasRequests.TaskHistory request) {
        return success(generationService.taskHistory(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/text/sessions")
    public AjaxResult textSessions(@Valid @RequestBody CanvasRequests.TaskHistory request) {
        return success(conversationStore.nodeSessions(request.getWorkspaceId(), request.getObjectId(),
                request.getPageNum(), request.getPageSize(), SecurityUtils.getUserId()));
    }

    @PostMapping("/generate/result/select")
    public AjaxResult selectResult(@Valid @RequestBody CanvasRequests.SelectResult request) {
        return success(generationService.selectResult(request, SecurityUtils.getUserId()));
    }

    @PostMapping("/text/result/adopt")
    public AjaxResult adoptTextResult(@Valid @RequestBody CanvasRequests.AdoptTextResult request) {
        return success(generationService.adoptTextResult(request, SecurityUtils.getUserId()));
    }

    @lombok.Data
    @lombok.EqualsAndHashCode(callSuper = true)
    public static class CanvasTaskRequest extends CanvasRequests.WorkspaceId {
        @jakarta.validation.constraints.NotNull private Long taskId;
    }

    private static void validateUploadRequest(List<MultipartFile> files, List<String> mediaTypes) {
        if (files == null || files.isEmpty() || files.size() > 20) throw new com.aid.common.exception.ServiceException("单次只能上传1到20个文件");
        if (mediaTypes == null || mediaTypes.size() != files.size()) throw new com.aid.common.exception.ServiceException("每个文件必须指定 mediaType");
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file == null || file.isEmpty()) throw new com.aid.common.exception.ServiceException("上传文件不能为空");
            String type = mediaTypes.get(i) == null ? "" : mediaTypes.get(i).trim().toUpperCase(Locale.ROOT);
            String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            String ext = dot < 0 ? "" : name.substring(dot + 1);
            Set<String> allowed = switch (type) {
                case "IMAGE" -> Set.of("jpg", "jpeg", "png", "webp", "gif", "bmp");
                case "VIDEO" -> Set.of("mp4", "mov", "webm", "m4v");
                case "AUDIO" -> Set.of("mp3", "wav", "m4a", "aac", "ogg", "flac");
                case "DOCUMENT" -> Set.of("pdf", "txt");
                default -> throw new com.aid.common.exception.ServiceException("mediaType 类型不支持");
            };
            if (!allowed.contains(ext)) throw new com.aid.common.exception.ServiceException("文件扩展名与 mediaType 不匹配: " + name);
        }
    }
}
