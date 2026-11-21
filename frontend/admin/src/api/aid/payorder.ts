import { request } from '@/utils/request'
import { invalidateSharedReads, sharedReadRequest } from '@/utils/sharedReadRequest'

export type RechargeChannel = 'alipay' | 'wxpay' | 'other'

export interface RechargeFilter {
  startDate: string
  endDate: string
  channel: RechargeChannel | 'all'
}

export interface RechargeDay {
  date: string
  amount: string
  orderCount: number
  payerCount: number
}

export interface RechargeOverview {
  totalAmount: string
  orderCount: number
  payerCount: number
  averageAmount: string
  trend: RechargeDay[]
  channels: { channel: RechargeChannel; amount: string; orderCount: number }[]
}

export const rechargeChannelLabels = {
  all: '全部渠道',
  alipay: '支付宝',
  wxpay: '微信支付',
  other: '其他渠道'
} satisfies Record<RechargeFilter['channel'], string>

const rechargeStatsUrl = '/aid/payorder/recharge-stats'

export function getRechargeOverview(filter: RechargeFilter): Promise<{ data: RechargeOverview }> {
  return sharedReadRequest(rechargeStatsUrl, { ...filter })
}

export function listRechargeOrders(filter: RechargeFilter, query: Record<string, unknown>) {
  return sharedReadRequest(`${rechargeStatsUrl}/orders`, { ...query, ...filter })
}

export function invalidateRechargeStats() {
  invalidateSharedReads(rechargeStatsUrl)
}

// 查询支付订单列表
export function listPayorder(query) {
  return request({
    url: '/aid/payorder/list',
    method: 'get',
    params: query
  })
}

// 查询支付订单详细
export function getPayorder(id) {
  return request({
    url: '/aid/payorder/' + id,
    method: 'get'
  })
}

// 新增支付订单
export function addPayorder(data) {
  return request({
    url: '/aid/payorder',
    method: 'post',
    data: data
  })
}

// 修改支付订单
export function updatePayorder(data) {
  return request({
    url: '/aid/payorder',
    method: 'put',
    data: data
  })
}

// 删除支付订单
export function delPayorder(id) {
  return request({
    url: '/aid/payorder/' + id,
    method: 'delete'
  })
}

// 同步支付宝订单状态
export function syncPayorder(orderNo) {
  return request({
    url: '/aid/payorder/sync/' + orderNo,
    method: 'post'
  }).then((result) => {
    invalidateRechargeStats()
    return result
  })
}

// 订单退款（后台运营操作）
export function refundPayorder(data) {
  return request({
    url: '/aid/payorder/refund',
    method: 'post',
    data: data
  }).then((result) => {
    invalidateRechargeStats()
    return result
  })
}
