package com.aid.canvas.publication.controller;
import com.aid.canvas.publication.service.CanvasPublicationBundleService;
import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
@RestController @RequestMapping("/api/user/canvas/publication/model") @RequiredArgsConstructor public class CanvasPublicationBundleController {
    private final CanvasPublicationBundleService service;
    @CryptoIgnore @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('aid:canvasModel:add')") @PostMapping("/upload") public AjaxResult upload(@RequestParam Long workspaceId,@RequestParam String operationId,@RequestParam String mediaType,@RequestParam String rootPath,@RequestParam("paths") List<String> paths,@RequestParam("files") List<MultipartFile> files) throws Exception {
        return AjaxResult.success(service.upload(workspaceId,SecurityUtils.getUserId(),operationId,mediaType,rootPath,paths,files));
    }
    @CryptoIgnore @PostMapping("/scene-asset")
    public AjaxResult sceneAsset(@RequestParam Long workspaceId, @RequestParam String operationId,
                                 @RequestParam("files") MultipartFile file) throws Exception {
        return AjaxResult.success(service.uploadSceneAsset(workspaceId, SecurityUtils.getUserId(), operationId, file));
    }
}
