package com.aid.canvas.controller;

import com.aid.canvas.model.*;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.aid.crypto.annotation.CryptoIgnore;
import com.aid.common.annotation.Log;
import com.aid.common.enums.BusinessType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/aid/canvas/model") @RequiredArgsConstructor
public class CanvasModelLibraryAdminController {
    private final CanvasModelLibraryService models;
    @PreAuthorize("@ss.hasPermi('aid:canvasModel:list')") @PostMapping("/list")
    public AjaxResult list(@Valid @RequestBody CanvasModelLibraryRequests.Query request){return AjaxResult.success(models.list(request,false));}
    @PreAuthorize("@ss.hasPermi('aid:canvasModel:list')") @PostMapping("/categories")
    public AjaxResult categories(){return AjaxResult.success(models.categories(false));}
    @PreAuthorize("@ss.hasPermi('aid:canvasModel:edit')") @PostMapping("/category/save")
    @Log(title="白模分类管理",businessType=BusinessType.UPDATE)
    public AjaxResult category(@Valid @RequestBody CanvasModelLibraryRequests.Category request){return AjaxResult.success((Object)models.category(request));}
    @PreAuthorize("@ss.hasPermi('aid:canvasModel:query')") @PostMapping("/detail")
    public AjaxResult detail(@Valid @RequestBody CanvasModelLibraryRequests.Key request){return AjaxResult.success(models.detail(request.getId()));}
    @PreAuthorize("@ss.hasPermi('aid:canvasModel:query')") @PostMapping("/preview")
    public AjaxResult preview(@Valid @RequestBody CanvasModelLibraryRequests.Key request){return AjaxResult.success(models.preview(request.getId(),false));}
    @PreAuthorize("@ss.hasPermi('aid:canvasModel:edit')") @PostMapping("/edit")
    @Log(title="白模版本管理",businessType=BusinessType.UPDATE)
    public AjaxResult edit(@Valid @RequestBody CanvasModelLibraryRequests.Edit request){
        if("DELETED".equals(request.getStatus()))throw new com.aid.common.exception.ServiceException("删除请使用删除接口");
        return AjaxResult.success(models.edit(request));
    }
    @PreAuthorize("@ss.hasPermi('aid:canvasModel:remove')") @PostMapping("/remove")
    @Log(title="白模版本管理",businessType=BusinessType.DELETE)
    public AjaxResult remove(@Valid @RequestBody CanvasModelLibraryRequests.Edit request){request.setStatus("DELETED");return AjaxResult.success(models.edit(request));}
    @CryptoIgnore @PreAuthorize("@ss.hasPermi('aid:canvasModel:add')") @PostMapping("/upload")
    @Log(title="白模版本上传",businessType=BusinessType.INSERT,isSaveRequestData=false)
    public AjaxResult upload(@RequestParam String name,@RequestParam String categoryId,@RequestParam(required=false) String modelId,
                            @RequestParam MultipartFile file,@RequestParam MultipartFile thumbnail) throws Exception {
        return AjaxResult.success(models.upload(name,categoryId,modelId,file,thumbnail));
    }
}
