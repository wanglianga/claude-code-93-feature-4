package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 队列变更时间线：记录排队记录每一次队列位置/台位/状态/权重/预计叫号时间的变化，
 * 是统一查询中「队列变更时间线」部分的数据来源。
 */
@Data
@Entity
@Table(name = "queue_transitions")
public class QueueTransition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ticketId;

    @Column(nullable = false, length = 16)
    private String ticketNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.QueueChangeType changeType;

    private Long fromCounterId;
    private Long toCounterId;

    @Column(length = 16)
    private String fromStatus;

    @Column(length = 16)
    private String toStatus;

    private Double weightBefore;
    private Double weightAfter;

    /** 变更时刻的已等待秒数 */
    private Long waitedSeconds;

    private LocalDateTime etaBefore;
    private LocalDateTime etaAfter;

    @Column(length = 300)
    private String reason;

    @Column(length = 32)
    private String actorName;

    private LocalDateTime createdAt;
}
