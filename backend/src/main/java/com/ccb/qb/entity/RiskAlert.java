package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 风控告警：同一客户重复取号 / 跨窗口频繁转台 / 人工插队。
 * 告警携带可追溯引用（关联事件/动作），且不得阻断已完成身份核验的紧急业务。
 */
@Data
@Entity
@Table(name = "risk_alerts")
public class RiskAlert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 告警编号，如 FK000001 */
    @Column(nullable = false, unique = true, length = 16)
    private String alertNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Enums.RiskAlertType alertType;

    private Long customerId;

    @Column(length = 32)
    private String customerName;

    /** 业务单号（可追溯至同一业务单号的全部协同记录） */
    @Column(length = 16)
    private String bizOrderNo;

    private Long ticketId;

    @Column(nullable = false, length = 400)
    private String detail;

    /** 追溯引用：触发告警的排队事件/处置动作 id 列表，如 "event:12,15" */
    @Column(length = 128)
    private String traceRef;

    /**
     * 是否阻断：身份已核验的紧急业务恒为 false（告警仅记录不阻断）；
     * 其他情形按规则可阻断（如未核验客户重复取号挂起待风控确认）。
     */
    private boolean blocking;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Enums.RiskAlertStatus status = Enums.RiskAlertStatus.OPEN;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime acknowledgedAt;

    @Column(length = 32)
    private String acknowledgedBy;
}
