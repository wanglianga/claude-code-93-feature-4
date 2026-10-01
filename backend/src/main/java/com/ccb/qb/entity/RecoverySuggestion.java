package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公平恢复建议：维修完成后生成，不自动把客户插回队首。
 * 依据故障时权重快照 + 当前窗口负载 + 同类业务服务能力计算建议台位与插入位置，
 * 需主管复核后方可应用。
 */
@Data
@Entity
@Table(name = "recovery_suggestions")
public class RecoverySuggestion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String bizOrderNo;

    @Column(nullable = false)
    private Long ticketId;

    @Column(nullable = false)
    private Long dispositionOrderId;

    @Column(nullable = false)
    private Long maintenanceOrderId;

    /** 建议恢复到的台位 */
    @Column(nullable = false)
    private Long suggestedCounterId;

    @Column(length = 16)
    private String suggestedCounterName;

    /** 建议插入位置（1 = 队首；按权重快照计算，不默认插队） */
    private int suggestedPosition;

    /** 计算依据 JSON：权重快照/当前负载/同类业务服务能力 */
    @Column(nullable = false, length = 1200)
    private String reasonJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Enums.SuggestionStatus status = Enums.SuggestionStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
