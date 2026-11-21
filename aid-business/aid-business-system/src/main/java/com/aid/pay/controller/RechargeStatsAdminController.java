package com.aid.pay.controller;

import com.aid.common.core.domain.AjaxResult;
import com.aid.pay.service.RechargeStatsService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员充值统计接口。 */
@RestController
@RequestMapping("/aid/payorder/recharge-stats")
@PreAuthorize("@ss.isBackendUser() and @ss.hasPermi('aid:payorder:list') "
        + "and (T(com.aid.common.utils.SecurityUtils).isAdmin() or @ss.hasRole('admin'))")
@RequiredArgsConstructor
public class RechargeStatsAdminController {
    private final RechargeStatsService rechargeStats;

    @GetMapping
    @Operation(summary = "查询充值金额统计",
            description = "按北京时间的支付日期和支付渠道汇总当前已支付充值，返回总计、每日趋势和渠道分布")
    public AjaxResult overview(@RequestParam String startDate, @RequestParam String endDate,
                               @RequestParam(defaultValue = "all") String channel) {
        return AjaxResult.success(rechargeStats.overview(startDate, endDate, channel));
    }

    @GetMapping("/orders")
    @Operation(summary = "查询充值统计对应订单",
            description = "按相同支付日期和支付渠道筛选已支付订单，支持按订单号和用户查询，按支付时间倒序分页")
    public AjaxResult orders(@RequestParam String startDate, @RequestParam String endDate,
                             @RequestParam(defaultValue = "all") String channel,
                             @RequestParam(required = false) String orderNo,
                             @RequestParam(required = false) Long userId,
                             @RequestParam(defaultValue = "1") int pageNum,
                             @RequestParam(defaultValue = "10") int pageSize) {
        return AjaxResult.success(rechargeStats.orders(startDate, endDate, channel, orderNo, userId, pageNum, pageSize));
    }
}
