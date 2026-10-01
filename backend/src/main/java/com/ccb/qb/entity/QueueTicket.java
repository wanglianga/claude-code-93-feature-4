package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 排队/预约主记录 —— 平台的核心单据。
 * 大堂经理、柜员、理财经理、安保、客服、客户六方围绕同一条记录协同。
 */
@Data
@Entity
@Table(name = "queue_tickets")
public class QueueTicket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务流水号，如 A003 / L001 */
    @Column(nullable = false, unique = true, length = 16)
    private String ticketNo;

    /** 客户（可为空：现场未登录客户由大堂经理代录） */
    private Long customerId;

    @Column(length = 32)
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Enums.CustomerType customerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Enums.Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Enums.BusinessType businessType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Enums.RiskLevel riskLevel;

    /** 是否老人 */
    private boolean elderly;

    /** 轮椅需求（无障碍通道） */
    private boolean wheelchair;

    /** 听力引导需求 */
    private boolean hearingAssist;

    /** 预约/预计办理时长（分钟） */
    private Integer estimatedMinutes;

    /** 预约时间（来源为预约时） */
    private LocalDateTime appointmentTime;

    /** 金额（大额现金/转账） */
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Enums.Material material = Enums.Material.COMPLETE;

    /** 缺件说明，如“未带身份证” */
    @Column(length = 255)
    private String materialNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.TicketStatus status = Enums.TicketStatus.WAITING;

    /** 分流到的渠道 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Enums.Channel channel;

    /** 分流到的窗口 */
    private Long counterId;

    /** 当前处理人 */
    private Long assigneeId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Enums.FraudStatus fraudStatus = Enums.FraudStatus.NONE;

    /** 反诈核验人（安保/复核岗） */
    private Long fraudCheckerId;
    private LocalDateTime fraudCheckedAt;
    @Column(length = 255)
    private String fraudNote;

    /** 适老服务安排 */
    private boolean escort;          // 陪同引导
    private boolean priorityWindow; // 优先窗口
    private boolean preReview;      // 材料预审
    private boolean familyConfirm;  // 家属远程确认
    @Column(length = 64)
    private String familyContact;

    private LocalDateTime createdAt;
    private LocalDateTime assignedAt;
    private LocalDateTime servingAt;
    private LocalDateTime finishedAt;

    /** 实际等待秒数（createdAt -> servingAt） */
    private Long waitSeconds;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Enums.ResultType resultType;

    @Column(length = 512)
    private String resultNote;

    /** 风险提示 */
    @Column(length = 512)
    private String riskNotice;

    /** 是否产生投诉 */
    private boolean complaint;
    @Column(length = 512)
    private String complaintNote;

    /** 回访结果 */
    @Column(length = 512)
    private String callbackNote;

    /** 长等提醒是否已发送 */
    private boolean longWaitNotified;

    /* ---------------- 排队权重 / 等待继承 / 叫号预估（台位异常处置特性） ---------------- */

    /** 优先级（越大越优先，由客户类型/优先窗口/紧急标记综合得出） */
    private int priority;

    /** 当前排队权重（优先级 + 等待时长老化，迁移时随单据继承） */
    private double queueWeight;

    /** 历史等待段累计秒数（设备异常/暂缓时冻结，恢复/迁移后继承） */
    private long accumulatedWaitSeconds;

    /** 当前等待段起点（为空表示等待计时冻结中） */
    private LocalDateTime waitStartedAt;

    /** 预计叫号时间（按所在台位队列与均件时长重算） */
    private LocalDateTime estimatedCallAt;

    /** 紧急业务 */
    private boolean urgent;

    /** 已完成身份核验（紧急业务风控告警不阻断的前提） */
    private boolean identityVerified;

    /** 转台次数（频繁转台风控信号） */
    private int transferCount;

    /** 待分配原因（迁移冲突回滚等可解释状态） */
    @Column(length = 255)
    private String pendingReason;

    /** 乐观锁：并发转台/重复提交时防止互相覆盖 */
    @Version
    private Long version;
}
