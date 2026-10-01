package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 处置动作记录（追加式，不修改）：前台选择的处置方案及结果。
 * idempotencyKey 唯一约束保证重复提交/网络重试不会产生双重效果。
 */
@Data
@Entity
@Table(name = "disposition_actions")
public class DispositionAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 处置单（转台尝试成功但未生成处置单时为空） */
    private Long dispositionOrderId;

    /** 业务单号（冗余，便于按单号统一查询） */
    @Column(nullable = false, length = 16)
    private String bizOrderNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Enums.DispositionActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Enums.DispositionActionResult result;

    private Long fromCounterId;

    private Long toCounterId;

    /** 动作后票据状态（如 ASSIGNED / PENDING_ASSIGN / DEFERRED） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Enums.TicketStatus ticketStatusAfter;

    /** 迁移后重算的预计叫号时间 */
    private LocalDateTime estimatedCallAtAfter;

    /** 迁移时继承的已等待秒数 */
    private Long inheritedWaitSeconds;

    @Column(length = 400)
    private String detail;

    /** 幂等键：同一处置动作重复提交时命中已有记录 */
    @Column(unique = true, length = 64)
    private String idempotencyKey;

    private Long actorId;

    @Column(length = 32)
    private String actorName;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
