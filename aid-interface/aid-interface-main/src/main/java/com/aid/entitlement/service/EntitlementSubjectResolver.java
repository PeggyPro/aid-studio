package com.aid.entitlement.service;

/**
 * 通用权益主体策略。业务域只声明主体策略，会员或团队模块以后可替换解析实现，
 * 无需在画布、导演台等调用点散落会员等级判断。
 *
 * <p>后续会员实现必须同时定义：主体来源与授权有效期、日/月等周期额度及用量账本、
 * 消费额度的预占/确认/退款状态机，以及实际扣款的钱包来源。权益主体解析本身不能
 * 自动切换付款人；当前媒体生成始终由操作人个人账户付款。</p>
 */
public interface EntitlementSubjectResolver {
    Subject resolve(Strategy strategy, Long actorUserId, Long projectOwnerUserId, Long teamId);

    enum Strategy { ACTOR, PROJECT_OWNER, TEAM }
    record Subject(String type, Long id) { }
}
