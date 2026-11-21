package com.aid.businessscene.controller;
import com.aid.businessscene.BusinessSceneStorageService;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.annotation.Log;
import com.aid.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/aid/funcconfig/entrypoints")
public class BusinessSceneStorageController extends BaseController {
    private final BusinessSceneStorageService service;
    public BusinessSceneStorageController(BusinessSceneStorageService service){this.service=service;}
    @GetMapping("/{scene}/storage-policy")
    @PreAuthorize("@ss.hasPermi('aid:funcconfig:query')")
    public AjaxResult read(@PathVariable("scene") String scene){return success(service.read(scene));}
    @PostMapping("/storage-policy")
    @PreAuthorize("@ss.hasPermi('aid:funcconfig:edit')")
    @Log(title="业务存储配置",businessType=BusinessType.UPDATE)
    public AjaxResult save(@RequestBody BusinessSceneStorageService.Save request){return success(service.save(request,getUsername()));}
}
