package com.aid.billing.enums;

/** 单次真实调用的预算预留状态。 */
public enum BillingReservationState {
    RESERVING,
    RESERVED,
    INFLIGHT,
    RECONCILING,
    SETTLED,
    REFUNDED,
    RELEASED
}
