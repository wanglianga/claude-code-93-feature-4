package com.ccb.qb.model;

/** 平台使用的全部枚举集中定义 */
public final class Enums {
    private Enums() {}

    /** 登录角色：客户/大堂经理(主管)/柜员/理财经理/安保/客服/前台/设备维修/风控 */
    public enum Role { CUSTOMER, MANAGER, TELLER, ADVISOR, SECURITY, SERVICE, FRONT_DESK, REPAIR, RISK }

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

    /** 排队记录状态：等待分流/已分配/办理中/异常挂起/待分配(设备异常回滚)/客户暂缓/已完成/已取消 */
    public enum TicketStatus { WAITING, ASSIGNED, SERVING, PENDING, PENDING_ALLOC, DEFERRED, DONE, CANCELLED }

    /** 引导渠道：柜台/自助机/理财室/远程客服 */
    public enum Channel { COUNTER, SELF_MACHINE, WEALTH_ROOM, REMOTE_SERVICE }

    /** 窗口类型：综合/现金/爱心优先/自助设备 */
    public enum CounterType { GENERAL, CASH, PRIORITY, SELF_SERVICE }

    public enum CounterStatus { OPEN, TEMP_LEAVE, CLOSED, FAULT, OFFLINE }

    /** 异常事件类型：长等/反诈/忘带证件/柜员离岗/自助机故障/投诉插队/停电/设备离线/转台失败 */
    public enum IssueType {
        NONE, LONG_WAIT, FRAUD_CHECK, MISSING_ID, TELLER_LEAVE,
        MACHINE_FAULT, JUMP_COMPLAINT, OUTAGE, DEVICE_OFFLINE, TRANSFER_FAILED
    }

    /** 反诈核验状态 */
    public enum FraudStatus { NONE, PENDING, PASSED, BLOCKED }

    public enum ResultType { SUCCESS, REJECTED, ESCALATED, REDIRECTED }

    public enum YesNo { OPEN, CLOSED }

    /* ---------------- 台位异常处置（特性：设备离线/转台失败四方协同） ---------------- */

    /** 处置单触发原因 */
    public enum FailureType { DEVICE_OFFLINE, TRANSFER_FAILED }

    /** 处置单流程状态：待处置/已采取措施/恢复建议待复核/已闭环 */
    public enum DispositionStatus { OPEN, ACTION_TAKEN, RECOVERY_PROPOSED, RESOLVED }
    /** 处置动作类型 */
    public enum DispositionActionType {
        MIGRATE,            // 保留原排队权重后迁移
        TO_MANUAL,          // 转人工窗口
        DEFER,              // 客户暂缓
        ROLLBACK_PENDING,   // 迁移冲突回滚至待分配
        TRANSFER,           // 手动转台
        RESUME,             // 暂缓后恢复排队
        RECOVERY_PROPOSE,   // 维修完成后生成公平恢复建议
        REVIEW_APPROVE,     // 主管复核通过
        REVIEW_REJECT,      // 主管复核退回
        REPAIR_START,       // 维修开始
        REPAIR_DONE,        // 维修完成
        NOTIFY              // 客户通知
    }
    /** 维修单状态 */
    public enum RepairStatus { OPEN, REPAIRING, DONE }
    /** 风控告警类型：重复取号/跨窗口频繁转台/人工插队 */
    public enum RiskAlertType { REPEAT_TICKET, FREQUENT_TRANSFER, MANUAL_JUMP }
    public enum RiskAlertStatus { OPEN, ACKNOWLEDGED }
    /** 队列变更时间线类型 */
    public enum QueueChangeType {
        ENQUEUE, ASSIGN, CALL, DONE, CANCEL,
        DEVICE_PENDING,     // 设备离线挂起为待分配
        MIGRATE,            // 迁移成功
        TO_MANUAL,          // 转人工窗口
        DEFER,              // 客户暂缓
        RESUME,             // 暂缓恢复
        ROLLBACK_PENDING,   // 迁移失败回滚待分配
        RECOVER,            // 维修后公平恢复
        ETA_UPDATE          // 预计叫号时间重算
    }
}
