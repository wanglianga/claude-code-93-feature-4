package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 处置单（不可篡改）：台位设备离线/转台失败时生成。
 * 内容字段在创建后不再修改（服务层不提供任何内容更新路径），
 * 并通过 prevHash/hash 构成哈希链，任何篡改都可被 /api/dispositions/verify-chain 检出。
 * 仅 status（处置进度）允许随处置动作流转。
 */
@Data
@Entity
@Table(name = "disposition_orders")
public class DispositionOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 处置单号，如 CZ000001 */
    @Column(nullable = false, unique = true, length = 16)
    private String orderNo;

    /** 业务单号（= 排队单号 ticketNo），四角色围绕同一业务单号协同 */
    @Column(nullable = false, length = 16)
    private String bizOrderNo;

    /** 关联排队记录 */
    @Column(nullable = false)
    private Long ticketId;

    /** 故障事件（维修单）：设备离线触发时关联；转台失败触发时可为空 */
    private Long maintenanceOrderId;

    /* ---------- 以下为不可篡改的快照内容 ---------- */

    private Long customerId;

    @Column(nullable = false, length = 32)
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Enums.BusinessType businessType;

    /** 优先级分快照 */
    private int priorityScore;

    /** 原台位 */
    private Long originalCounterId;

    @Column(length = 16)
    private String originalCounterName;

    /** 故障时已等待秒数 */
    private long waitedSeconds;

    /** 故障发生时的权重快照 */
    private long weightSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.FailureType failureType;

    @Column(nullable = false, length = 255)
    private String failureReason;

    /** 队列快照：故障时在该台位前方的排队人数 */
    private int queueAhead;

    /* ---------- 哈希链 ---------- */

    @Column(nullable = false, length = 64)
    private String prevHash;

    @Column(nullable = false, length = 64)
    private String hash;

    /* ---------- 可流转的处置进度（非内容字段） ---------- */

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Enums.DispositionStatus status = Enums.DispositionStatus.OPEN;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
