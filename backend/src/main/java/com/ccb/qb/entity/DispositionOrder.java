package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 台位异常处置单（不可篡改快照 + 哈希链）。
 * 客户已取号且已生成叫号预估后，台位设备离线或转台失败时生成：
 * 快照记录客户、队列、业务类型、优先级、原台位、已等待时长与失败原因，
 * 以同一业务单号（ticketNo）驱动前台、柜台、设备维修、风控四个角色协同。
 *
 * 快照字段（orderNo..failureReason、prevHash/recordHash、createdAt）写入后不再更新；
 * 仅流程字段（status/resolutionNote/recoverySuggestion/resolvedAt）随协同推进。
 * recordHash = sha256(快照内容 + prevHash)，全链可校验，任何事后篡改都会导致链校验失败。
 */
@Data
@Entity
@Table(name = "disposition_orders")
public class DispositionOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 处置单号：D<业务单号>-<序号>，如 DA002-1 */
    @Column(nullable = false, unique = true, length = 24)
    private String orderNo;

    /* ---------- 以下为不可篡改快照 ---------- */

    private Long ticketId;

    /** 业务单号（与排队记录一致，四方协同的统一键） */
    @Column(nullable = false, length = 16)
    private String ticketNo;

    private Long customerId;

    @Column(length = 32)
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Enums.BusinessType businessType;

    /** 优先级快照（越大越优先） */
    private int priority;

    /** 故障发生时的排队权重快照 */
    private double weightSnapshot;

    /** 故障发生时所在队列位置（1 起；0 表示正在办理） */
    private int queuePosition;

    /** 原台位 */
    private Long originalCounterId;

    @Column(length = 16)
    private String originalCounterName;

    /** 已等待时长（秒，故障发生时冻结） */
    private long waitedSeconds;

    /** 故障发生时的预计叫号时间 */
    private LocalDateTime estimatedCallAtSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.FailureType failureType;

    @Column(length = 255)
    private String failureReason;

    /** 哈希链：上一条处置单的 recordHash（首条为 GENESIS） */
    @Column(nullable = false, length = 64)
    private String prevHash;

    /** 本记录哈希：sha256(快照字段 + prevHash) */
    @Column(nullable = false, length = 64)
    private String recordHash;

    private LocalDateTime createdAt;

    /* ---------- 以下为流程字段（随协同推进，不属于快照） ---------- */

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.DispositionStatus status = Enums.DispositionStatus.OPEN;

    /** 关联维修单（设备离线触发时） */
    private Long repairId;

    /** 维修完成后生成的公平恢复建议（JSON：建议台位/插入位置/依据） */
    @Column(length = 1000)
    private String recoverySuggestion;

    @Column(length = 300)
    private String resolutionNote;

    private LocalDateTime resolvedAt;
}
