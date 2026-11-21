package com.aid.compute.controller;

import com.aid.compute.ComputeNodeConfigurationService;
import com.aid.compute.ComputeNodeProbeService;
import com.aid.compute.ComputeNodeActivationService;
import com.aid.compute.ComputeNodeHealthService;
import com.aid.common.annotation.Log;
import com.aid.common.core.controller.BaseController;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.core.page.TableDataInfo;
import com.aid.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/aid/compute/node")
public class ComputeNodeController extends BaseController {
    private final ComputeNodeConfigurationService service;
    private final ComputeNodeProbeService probes;
    private final ComputeNodeActivationService activation;
    private final ComputeNodeHealthService health;
    public ComputeNodeController(ComputeNodeConfigurationService service,ComputeNodeProbeService probes,ComputeNodeActivationService activation,ComputeNodeHealthService health){this.service=service;this.probes=probes;this.activation=activation;this.health=health;}
    public record CertificateInput(String pem) { }
    @PostMapping("/certificate/inspect") @PreAuthorize("@ss.hasPermi('aid:compute:edit')")
    public AjaxResult inspectCertificate(@RequestBody CertificateInput input){return success(com.aid.compute.ComputeTlsSupport.inspect(input.pem()));}
    public record Maintenance(String nodeKey,boolean enabled) { }
    @PostMapping("/health") @PreAuthorize("@ss.hasPermi('aid:compute:edit')")
    @Log(title="检查计算节点健康",businessType=BusinessType.OTHER,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult health(@RequestBody ComputeNodeActivationService.Activate input){return success(health.refresh(input.nodeKey(),getUserId()));}
    @PostMapping("/maintenance") @PreAuthorize("@ss.hasPermi('aid:compute:edit')")
    @Log(title="切换计算节点维护",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult maintenance(@RequestBody Maintenance input){health.maintenance(input.nodeKey(),input.enabled());return success(service.detail(input.nodeKey()));}
    @PostMapping("/activate") @PreAuthorize("@ss.hasPermi('aid:compute:edit')")
    @Log(title="启用计算节点配置",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult activate(@RequestBody ComputeNodeActivationService.Activate input){return success(activation.activate(input,getUsername()));}
    @PostMapping("/probe") @PreAuthorize("@ss.hasPermi('aid:compute:edit')")
    @Log(title="计算节点在线检测",businessType=BusinessType.OTHER,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult probe(@RequestBody ComputeNodeProbeService.Connection connection){return success(probes.probe(connection,getUserId()));}
    @GetMapping("/list") @PreAuthorize("@ss.hasPermi('aid:compute:query')")
    public TableDataInfo list(@RequestParam(name="keyword",required=false)String keyword){
        startPage();var rows=service.listRows(keyword);var table=getDataTable(rows);
        table.setRows(rows.stream().map(service::view).toList());return table;
    }
    @GetMapping("/{key}") @PreAuthorize("@ss.hasPermi('aid:compute:query')")
    public AjaxResult detail(@PathVariable("key") String key){return success(service.detail(key));}
    @PostMapping @PreAuthorize("@ss.hasPermi('aid:compute:edit')")
    @Log(title="计算节点配置",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult save(@RequestBody ComputeNodeConfigurationService.Save input){return success(service.save(input,getUsername(),getUserId()));}
}
