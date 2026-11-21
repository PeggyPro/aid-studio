package com.aid.aid.controller;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.annotation.Log;
import com.aid.common.core.domain.AjaxResult;
import com.aid.common.enums.BusinessType;
import com.aid.common.utils.SecurityUtils;
import com.aid.discovery.dto.AdminDiscoveryPageRequest;
import com.aid.discovery.dto.AdminDiscoveryReviewRequest;
import com.aid.discovery.dto.AdminDiscoverySaveRequest;
import com.aid.discovery.dto.AdminDiscoveryUpdateRequest;
import com.aid.discovery.dto.DiscoveryDetailRequest;
import com.aid.discovery.service.IDiscoveryMediaBusinessService;
import com.aid.discovery.vo.DiscoveryManageVO;
import com.aid.discovery.vo.DiscoveryPageResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Manager endpoints for discovery content, kept separate from work review. */
@RestController
@RequestMapping("/aid/discovery")
@RequiredArgsConstructor
@Tag(name = "发现内容管理", description = "管理发现内容投放和独立投稿审核，不参与项目流程审核")
public class AidDiscoveryController
{
    private final IDiscoveryMediaBusinessService discoveryMediaBusinessService;

    @PreAuthorize("@ss.hasPermi('commercial:discovery:list')")
    @PostMapping("/list")
    @Operation(summary = "分页查询发现内容", description = "按名称、媒体类型、来源和审核状态筛选；列表不返回提示词")
    public AjaxResult list(@Valid @RequestBody(required = false) AdminDiscoveryPageRequest request)
    {
        DiscoveryPageResult<DiscoveryManageVO> page = discoveryMediaBusinessService.pageAdmin(request);
        AjaxResult result = AjaxResult.success();
        result.put("total", page.getTotal());
        result.put("data", page.getData());
        return result;
    }

    @PreAuthorize("@ss.hasPermi('commercial:discovery:query')")
    @PostMapping("/detail")
    @Operation(summary = "查询发现内容详情", description = "返回管理字段、预留提示词和按时间倒序的独立审核操作记录，仅管理员可用")
    public AjaxResult detail(@Valid @RequestBody DiscoveryDetailRequest request)
    {
        return AjaxResult.success(discoveryMediaBusinessService.getAdmin(request.getId()));
    }

    @PreAuthorize("@ss.hasPermi('commercial:discovery:add')")
    @Log(title = "发现内容", businessType = BusinessType.INSERT)
    @PostMapping("/add")
    @Operation(summary = "新增并发布发现内容", description = "管理员可新增图片或视频；视频必须有封面，图片复用封面字段")
    public AjaxResult add(@Valid @RequestBody AdminDiscoverySaveRequest request)
    {
        Long id = discoveryMediaBusinessService.createAdmin(
                request, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success("新增成功", Map.of("id", id));
    }

    @PreAuthorize("@ss.hasPermi('commercial:discovery:edit')")
    @Log(title = "发现内容", businessType = BusinessType.UPDATE)
    @PostMapping("/edit")
    @Operation(summary = "编辑发现内容", description = "管理员编辑后直接生效并记录操作日志，不改变当前审核状态")
    public AjaxResult edit(@Valid @RequestBody AdminDiscoveryUpdateRequest request)
    {
        discoveryMediaBusinessService.updateAdmin(
                request, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success("修改成功");
    }

    @PreAuthorize("@ss.hasPermi('commercial:discovery:review')")
    @Log(title = "发现投稿审核", businessType = BusinessType.UPDATE)
    @PostMapping("/review")
    @Operation(summary = "审核或恢复发现内容", description = "支持通过、拒绝、撤销；拒绝和撤销原因必填，管理员自建内容撤销后可恢复发布")
    public AjaxResult review(@Valid @RequestBody AdminDiscoveryReviewRequest request)
    {
        discoveryMediaBusinessService.reviewAdmin(
                request, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success("操作成功");
    }

    @PreAuthorize("@ss.hasPermi('commercial:discovery:remove')")
    @Log(title = "发现内容", businessType = BusinessType.DELETE)
    @PostMapping("/remove")
    @Operation(summary = "删除发现内容", description = "软删除并立即停止公开展示，同时写入操作记录")
    public AjaxResult remove(@Valid @RequestBody DiscoveryDetailRequest request)
    {
        discoveryMediaBusinessService.deleteAdmin(
                request.getId(), SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success("删除成功");
    }
}
