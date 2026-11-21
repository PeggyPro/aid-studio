package com.aid.discovery.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aid.common.annotation.Anonymous;
import com.aid.common.core.domain.AjaxResult;
import com.aid.discovery.dto.DiscoveryDetailRequest;
import com.aid.discovery.dto.DiscoveryPageRequest;
import com.aid.discovery.service.IDiscoveryMediaBusinessService;
import com.aid.discovery.vo.DiscoveryPageResult;
import com.aid.discovery.vo.DiscoveryPublicVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Anonymous discovery feed endpoints. */
@RestController
@RequestMapping("/api/public/discovery")
@RequiredArgsConstructor
@Tag(name = "公开发现内容", description = "无需登录的发现瀑布流和详情接口")
public class PublicDiscoveryController
{
    private final IDiscoveryMediaBusinessService discoveryMediaBusinessService;

    @Anonymous
    @PostMapping("/page")
    @Operation(summary = "分页查询公开发现内容",
            description = "无需登录，返回已审核通过且已启用的公开内容，不区分发布来源；分页字段位于响应顶层")
    public AjaxResult page(@Valid @RequestBody(required = false) DiscoveryPageRequest request)
    {
        DiscoveryPageResult<DiscoveryPublicVO> page = discoveryMediaBusinessService.pagePublic(request);
        AjaxResult result = AjaxResult.success();
        result.put("total", page.getTotal());
        result.put("data", page.getData());
        return result;
    }

    @Anonymous
    @PostMapping("/detail")
    @Operation(summary = "查询公开发现内容详情", description = "不返回用户编号、审核原因、审核人员或预留提示词")
    public AjaxResult detail(@Valid @RequestBody DiscoveryDetailRequest request)
    {
        return AjaxResult.success(discoveryMediaBusinessService.getPublic(request.getId()));
    }
}
