package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 风控告警：重复取号 / 跨窗口频繁转台 / 人工插队。
 * 每条告警带 traceId 可全链路追溯；
 * 告警只记录与提示，不阻断业务——对已完成身份核验的紧急业务，
 * urgentVerifiedBypass=true 明确标记「不阻断」原因。
 */
@Data
@Entity
@Table(name = "risk_alerts")
public class RiskAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 告警编号 RAyyyyMMdd-序号 */
    @Column(nullable = false, unique = true, length = 24)
    private String alertNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Enums.RiskAlertType alertType;

    private Long ticketId;

    @Column(length = 16)
    private String ticketNo;

    private Long customerId;

    @Column(length = 32)
    private String customerName;

    /** 关联处置单（转台类告警） */
    private Long dispositionId;

    @Column(length = 500)
    private String detail;

    /** 全链路追溯号 */
    @Column(nullable = false, length = 36)
    private String traceId;

    /** 紧急业务且已完成身份核验：告警不阻断 */
    private boolean urgentVerifiedBypass;

    @Column(length = 200)
    private String bypassNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Enums.RiskAlertStatus status = Enums.RiskAlertStatus.OPEN;

    @Column(length = 32)
    private String ackBy;

    @Column(length = 300)
    private String ackNote;

    private LocalDateTime createdAt;
    private LocalDateTime ackAt;
}
