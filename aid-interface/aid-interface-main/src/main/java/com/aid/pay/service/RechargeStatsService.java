package com.aid.pay.service;

import com.aid.aid.domain.AidPayOrder;
import com.aid.aid.service.IAidPayOrderService;
import com.aid.common.constant.PayConstants;
import com.aid.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 已支付充值的统计和订单下钻。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeStatsService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(BUSINESS_ZONE);
    private static final LocalDate EARLIEST_DATE = LocalDate.of(1970, 1, 1);
    private static final int MAX_DAYS = 366;
    private static final String ALL = "all";
    private static final String OTHER = "other";
    private static final List<String> KNOWN_CHANNELS = List.of(PayConstants.CHANNEL_ALIPAY, PayConstants.CHANNEL_WXPAY);
    private static final Set<String> FILTER_CHANNELS = Set.of(ALL, OTHER,
            PayConstants.CHANNEL_ALIPAY, PayConstants.CHANNEL_WXPAY);
    private static final String CHANNEL_GROUP = "CASE WHEN pay_channel IN ('"
            + String.join("', '", KNOWN_CHANNELS) + "') THEN LOWER(TRIM(pay_channel)) ELSE 'other' END";
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");
    private static final Totals EMPTY_TOTALS = new Totals(ZERO_AMOUNT, 0, 0);

    private final IAidPayOrderService payOrders;

    public Overview overview(String startDate, String endDate, String channel) {
        DateRange range = validate(startDate, endDate, channel);
        // 同一条聚合查询返回每日渠道、每日汇总及总计。跨日、跨渠道的充值人数由数据库去重。
        List<Map<String, Object>> rows = payOrders.listMaps(paidOrders(range, channel)
                .select("DATE_FORMAT(pay_time, '%Y-%m-%d') AS payment_day", CHANNEL_GROUP + " AS channel_group",
                        "SUM(pay_price) AS amount", "COUNT(*) AS order_count", "COUNT(DISTINCT user_id) AS payer_count")
                .groupBy("payment_day", "channel_group")
                .last("WITH ROLLUP"));

        Totals total = EMPTY_TOTALS;
        Map<String, Totals> days = new HashMap<>();
        Map<String, Totals> channels = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String day = (String) row.get("payment_day");
            String group = (String) row.get("channel_group");
            BigDecimal amount = Objects.requireNonNullElse((BigDecimal) row.get("amount"), ZERO_AMOUNT);
            long orders = ((Number) row.get("order_count")).longValue();
            long payers = ((Number) row.get("payer_count")).longValue();
            if (day == null) {
                total = new Totals(amount, orders, payers);
            } else if (group == null) {
                days.put(day, new Totals(amount, orders, payers));
            } else {
                channels.merge(group, new Totals(amount, orders, 0), Totals::addChannelDay);
            }
        }

        List<Daily> trend = new ArrayList<>();
        for (LocalDate date = range.start(); !date.isAfter(range.end()); date = date.plusDays(1)) {
            Totals daily = days.getOrDefault(date.toString(), EMPTY_TOTALS);
            trend.add(new Daily(date.toString(), daily.amount().toPlainString(), daily.orders(), daily.payers()));
        }
        List<Channel> breakdown = channels.entrySet().stream()
                .sorted(Map.Entry.<String, Totals>comparingByValue(Comparator.comparing(Totals::amount))
                        .reversed().thenComparing(Map.Entry::getKey))
                .map(entry -> new Channel(entry.getKey(), entry.getValue().amount().toPlainString(),
                        entry.getValue().orders()))
                .toList();
        BigDecimal average = total.orders() == 0 ? ZERO_AMOUNT
                : total.amount().divide(BigDecimal.valueOf(total.orders()), 2, RoundingMode.HALF_UP);
        return new Overview(total.amount().toPlainString(), total.orders(), total.payers(),
                average.toPlainString(), trend, breakdown);
    }

    public Orders orders(String startDate, String endDate, String channel,
                         String orderNo, Long userId, int pageNum, int pageSize) {
        DateRange range = validate(startDate, endDate, channel);
        if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
            log.info("充值统计订单分页参数无效");
            throw new ServiceException("分页参数无效");
        }
        QueryWrapper<AidPayOrder> query = paidOrders(range, channel)
                .select("id", "order_no", "user_id", "product_name", "credits",
                        "pay_price", "pay_channel", "pay_status", "pay_time", "create_time")
                .like(orderNo != null && !orderNo.isBlank(), "order_no", orderNo)
                .eq(userId != null, "user_id", userId)
                .orderByDesc("pay_time", "id");
        PageInfo<AidPayOrder> page = PageHelper.<AidPayOrder>startPage(pageNum, pageSize)
                .doSelectPageInfo(() -> payOrders.list(query));
        List<OrderRow> rows = page.getList().stream()
                .map(order -> new OrderRow(order.getId(), order.getOrderNo(), order.getUserId(), order.getProductName(),
                        order.getCredits(), order.getPayPrice(), order.getPayChannel(), order.getPayStatus(),
                        formatTime(order.getPayTime()), formatTime(order.getCreateTime())))
                .toList();
        return new Orders(rows, page.getTotal());
    }

    private static String formatTime(Date time) {
        return time == null ? null : ORDER_TIME.format(time.toInstant());
    }

    private QueryWrapper<AidPayOrder> paidOrders(DateRange range, String channel) {
        QueryWrapper<AidPayOrder> query = Wrappers.<AidPayOrder>query()
                .eq("pay_status", PayConstants.STATUS_PAID)
                .eq("del_flag", "0")
                .ge("pay_time", range.start().atStartOfDay())
                .lt("pay_time", range.end().plusDays(1).atStartOfDay());
        if (OTHER.equals(channel)) {
            query.and(group -> group.isNull("pay_channel").or().notIn("pay_channel", KNOWN_CHANNELS));
        } else {
            query.eq(!ALL.equals(channel), "pay_channel", channel);
        }
        return query;
    }

    private DateRange validate(String startDate, String endDate, String channel) {
        if (!FILTER_CHANNELS.contains(channel)) {
            log.info("充值统计渠道参数无效");
            throw new ServiceException("支付渠道无效");
        }
        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            if (!start.isBefore(EARLIEST_DATE) && !end.isBefore(start)
                    && !end.isAfter(LocalDate.now(BUSINESS_ZONE)) && ChronoUnit.DAYS.between(start, end) < MAX_DAYS) {
                return new DateRange(start, end);
            }
        } catch (DateTimeParseException ignored) {
            // 非法日期与范围错误统一返回业务提示。
        }
        log.info("充值统计日期范围无效");
        throw new ServiceException("日期范围无效");
    }

    private record DateRange(LocalDate start, LocalDate end) {}

    private record Totals(BigDecimal amount, long orders, long payers) {
        Totals addChannelDay(Totals day) {
            return new Totals(amount.add(day.amount), orders + day.orders, 0);
        }
    }

    public record Overview(String totalAmount, long orderCount, long payerCount, String averageAmount,
                           List<Daily> trend, List<Channel> channels) {}

    public record Daily(String date, String amount, long orderCount, long payerCount) {}

    public record Channel(String channel, String amount, long orderCount) {}

    public record Orders(List<OrderRow> rows, long total) {}

    public record OrderRow(Long id, String orderNo, Long userId, String productName, BigDecimal credits,
                           BigDecimal payPrice, String payChannel, String payStatus, String payTime, String createTime) {}
}
