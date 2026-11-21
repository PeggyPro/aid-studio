package com.aid.canvas.controller;

import com.aid.canvas.model.*;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/user/canvas/model") @RequiredArgsConstructor
public class CanvasModelLibraryUserController {
    private final CanvasModelLibraryService models;
    @PostMapping("/list") public AjaxResult list(@Valid @RequestBody CanvasModelLibraryRequests.Query request){return AjaxResult.success(models.list(request,true));}
    @PostMapping("/categories") public AjaxResult categories(){return AjaxResult.success(models.categories(true));}
    @PostMapping("/preview") public AjaxResult preview(@Valid @RequestBody CanvasModelLibraryRequests.Key request){return AjaxResult.success(models.preview(request.getId(),true));}
    @PostMapping("/use") public AjaxResult use(@Valid @RequestBody CanvasModelLibraryRequests.Use request){return AjaxResult.success(models.use(request,SecurityUtils.getUserId()));}
    @PostMapping("/resources/read") public AjaxResult resources(@Valid @RequestBody CanvasModelLibraryRequests.Resources request){return AjaxResult.success(models.readResources(request,SecurityUtils.getUserId()));}
}
