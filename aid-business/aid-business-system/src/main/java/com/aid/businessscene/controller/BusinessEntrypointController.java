package com.aid.businessscene.controller;

import com.aid.businessscene.BusinessEntrypointService;
import com.aid.businessscene.BusinessSceneComputePolicyService;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.annotation.Log;
import com.aid.common.enums.BusinessType;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController @RequestMapping("/aid/funcconfig/entrypoints")
public class BusinessEntrypointController extends BaseController {
    private final BusinessEntrypointService service;
    private final BusinessSceneComputePolicyService compute;
    public BusinessEntrypointController(BusinessEntrypointService service,BusinessSceneComputePolicyService compute){this.service=service;this.compute=compute;}
    @GetMapping("/{scene}/compute-pool") @PreAuthorize("@ss.hasPermi('aid:funcconfig:query')")
    public AjaxResult compute(@PathVariable("scene")String scene){return success(compute.read(scene));}
    @PostMapping("/compute-pool") @PreAuthorize("@ss.hasPermi('aid:funcconfig:edit')")
    @Log(title="业务场景计算资源池",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult compute(@RequestBody BusinessSceneComputePolicyService.Save input){return success(compute.save(input,getUsername()));}
    @GetMapping("/{scene}/options") @PreAuthorize("@ss.hasPermi('aid:funcconfig:query')")
    public AjaxResult options(@PathVariable("scene")String scene){return success(service.options(scene));}
    @GetMapping("/{scene}") @PreAuthorize("@ss.hasPermi('aid:funcconfig:query')")
    public AjaxResult read(@PathVariable("scene")String scene){return success(service.read(scene));}
    @PostMapping @PreAuthorize("@ss.hasPermi('aid:funcconfig:edit')")
    @Log(title="业务场景入口绑定",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult save(@RequestBody BusinessEntrypointService.Save input){return success(service.save(input,getUsername()));}
}
