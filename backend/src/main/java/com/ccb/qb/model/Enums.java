package com.ccb.qb.model;

/** 平台使用的全部枚举集中定义 */
public final class Enums {
    private Enums() {}

    /** 六类登录角色 + 客户 + 特性4新增：设备维修/风控/主管 */
    public enum Role { CUSTOMER, MANAGER, TELLER, ADVISOR, SECURITY, SERVICE, MAINTENANCE, RISK, SUPERVISOR }

    /** 客户类型 */
    public enum CustomerType { NORMAL, VIP, ELDERLY, CORPORATE }

    /** 预约/取号来源 */
    public enum Source { WALK_IN, MOBILE_APP, PHONE, HOTLINE_ELDERLY }

    /** 业务类型：开户/转账/挂失/社保卡/理财咨询/大额现金 */
    public enum BusinessType {
        ACCOUNT_OPEN, TRANSFER, LOST_REPORT, SOCIAL_CARD, WEALTH_CONSULT, LARGE_CASH
    }

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    /** 材料完整度 */
    public enum Material { COMPLETE, INCOMPLETE, MISSING }

    /**
     * 排队记录状态：等待分流/已分配/办理中/异常挂起/已完成/已取消
     * 特性4新增：DISPOSING 故障处置中 / PENDING_ASSIGN 待分配（迁移回滚后的可解释状态）/ DEFERRED 客户暂缓
     */
    public enum TicketStatus { WAITING, ASSIGNED, SERVING, PENDING, DONE, CANCELLED, DISPOSING, PENDING_ASSIGN, DEFERRED }

    /** 引导渠道：柜台/自助机/理财室/远程客服 */
    public enum Channel { COUNTER, SELF_MACHINE, WEALTH_ROOM, REMOTE_SERVICE }

    /** 窗口类型：综合/现金/爱心优先/自助设备 */
    public enum CounterType { GENERAL, CASH, PRIORITY, SELF_SERVICE }

    /** 台位状态：开放/临时离岗/关闭/故障/设备离线（特性4） */
    public enum CounterStatus { OPEN, TEMP_LEAVE, CLOSED, FAULT, OFFLINE }

    /** 异常事件类型 */
    public enum IssueType {
        NONE, LONG_WAIT, FRAUD_CHECK, MISSING_ID, TELLER_LEAVE,
        MACHINE_FAULT, JUMP_COMPLAINT, OUTAGE,
        /* 特性4：设备离线/转台失败处置时间线 */
        DEVICE_OFFLINE, TRANSFER_FAILED, QUEUE_MIGRATED, CUSTOMER_DEFERRED,
        PENDING_ASSIGN, RECOVERY_SUGGESTED, RECOVERY_APPLIED, RISK_ALERT
    }

    /** 反诈核验状态 */
    public enum FraudStatus { NONE, PENDING, PASSED, BLOCKED }

    public enum ResultType { SUCCESS, REJECTED, ESCALATED, REDIRECTED }

    public enum YesNo { OPEN, CLOSED }

    /* ================= 特性4：设备离线/转台失败处置 ================= */

    /** 失败原因类型：设备离线 / 转台失败 */
    public enum FailureType { DEVICE_OFFLINE, TRANSFER_FAILED }

    /** 处置单状态：待处置 / 已处置（迁移/转人工/暂缓） / 已恢复（维修闭环后重新入队） */
    public enum DispositionStatus { OPEN, RESOLVED, RECOVERED }

    /** 处置方案：保留原排队权重后迁移 / 转人工窗口 / 客户暂缓 / 维修后公平恢复 */
    public enum DispositionActionType { MIGRATE_KEEP_WEIGHT, TO_MANUAL, DEFER, RECOVER }

    /** 处置动作结果：成功 / 回滚（目标台位被占用，转入可解释的待分配状态） */
    public enum DispositionActionResult { SUCCESS, ROLLED_BACK }

    /** 维修单状态：待接单 / 维修中 / 已完成 */
    public enum MaintenanceStatus { OPEN, REPAIRING, DONE }

    /** 恢复建议状态：待主管复核 / 已批准 / 已驳回 / 已应用 */
    public enum SuggestionStatus { PENDING, APPROVED, REJECTED, APPLIED }

    public enum ReviewDecision { APPROVE, REJECT }

    /** 风控告警类型：同一客户重复取号 / 跨窗口频繁转台 / 人工插队 */
    public enum RiskAlertType { REPEAT_TICKET, FREQUENT_TRANSFER, MANUAL_JUMP }

    /** 告警状态：待确认 / 已确认 */
    public enum RiskAlertStatus { OPEN, ACKNOWLEDGED }

    /** 客户通知渠道 */
    public enum NotifyChannel { SMS, APP, VOICE }

    /** 设备状态上报处理结果：已应用 / 重复上报（幂等命中） / 状态未变化 */
    public enum DeviceReportResult { APPLIED, DUPLICATE, NOOP }
}
