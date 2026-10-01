package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 处置动作流水：前台/柜台/设备维修/风控/主管在处置单上的每一步操作。
 * idempotencyKey 唯一约束保证重复提交不会产生重复动作。
 */
@Data
@Entity
@Table(name = "disposition_actions")
public class DispositionAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long dispositionId;

    @Column(nullable = false, length = 24)
    private String orderNo;

    /** 业务单号（冗余，便于按单号统一查询） */
    @Column(nullable = false, length = 16)
    private String ticketNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.DispositionActionType actionType;

    private Long actorId;

    @Column(length = 32)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Enums.Role actorRole;

    @Column(length = 500)
    private String detail;

    /** 幂等键（客户端每次操作生成；唯一约束防重复提交） */
    @Column(unique = true, length = 64)
    private String idempotencyKey;

    private LocalDateTime createdAt;
}
