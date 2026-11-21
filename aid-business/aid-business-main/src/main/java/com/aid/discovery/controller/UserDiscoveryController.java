package com.aid.discovery.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.utils.SecurityUtils;
import com.aid.discovery.dto.DiscoveryDetailRequest;
import com.aid.discovery.dto.UserDiscoveryPageRequest;
import com.aid.discovery.dto.UserDiscoveryResubmitRequest;
import com.aid.discovery.dto.UserDiscoverySubmitRequest;
import com.aid.discovery.service.IDiscoveryMediaBusinessService;
import com.aid.discovery.vo.DiscoveryManageVO;
import com.aid.discovery.vo.DiscoveryPageResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Authenticated user discovery submission endpoints. */
@RestController
@RequestMapping("/api/user/discovery")
@RequiredArgsConstructor
@Tag(name = "C端发现投稿", description = "用户视频投稿、状态查询、重新提交和删除接口")
public class UserDiscoveryController
{
    private final IDiscoveryMediaBusinessService discoveryMediaBusinessService;

    @PostMapping("/submit")
    @Operation(summary = "提交发现视频", description = "仅支持视频且必须提供封面，提交后进入独立待审核状态")
    public AjaxResult submit(@Valid @RequestBody UserDiscoverySubmitRequest request)
    {
        Long id = discoveryMediaBusinessService.submitUser(
                request, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success("投稿已提交，等待审核", Map.of("id", id));
    }

    @PostMapping("/my/page")
    @Operation(summary = "分页查询我的发现投稿", description = "只返回当前用户投稿，不暴露后台审核人员账号")
    public AjaxResult myPage(@Valid @RequestBody(required = false) UserDiscoveryPageRequest request)
    {
        DiscoveryPageResult<DiscoveryManageVO> page = discoveryMediaBusinessService.pageUser(
                request, SecurityUtils.getUserId());
        AjaxResult result = AjaxResult.success();
        result.put("total", page.getTotal());
        result.put("data", page.getData());
        return result;
    }

    @PostMapping("/my/detail")
    @Operation(summary = "查询我的发现投稿详情", description = "返回审核状态、最近原因与时间，不返回管理预留提示词")
    public AjaxResult myDetail(@Valid @RequestBody DiscoveryDetailRequest request)
    {
        return AjaxResult.success(discoveryMediaBusinessService.getUser(request.getId(), SecurityUtils.getUserId()));
    }

    @PostMapping("/resubmit")
    @Operation(summary = "重新提交发现视频", description = "仅被拒绝或撤销的本人视频可重新提交并回到待审核状态")
    public AjaxResult resubmit(@Valid @RequestBody UserDiscoveryResubmitRequest request)
    {
        discoveryMediaBusinessService.resubmitUser(
                request, SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success("投稿已重新提交，等待审核");
    }

    @PostMapping("/delete")
    @Operation(summary = "删除我的发现投稿", description = "删除本人投稿；已公开内容会立即停止展示")
    public AjaxResult delete(@Valid @RequestBody DiscoveryDetailRequest request)
    {
        discoveryMediaBusinessService.deleteUser(
                request.getId(), SecurityUtils.getUserId(), SecurityUtils.getUsername());
        return AjaxResult.success("投稿已删除");
    }
}
