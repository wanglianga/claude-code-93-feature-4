package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 客户通知：处置/迁移/回滚/暂缓/恢复等关键节点向客户发送的通知（演示环境落库模拟） */
@Data
@Entity
@Table(name = "notifications")
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String bizOrderNo;

    private Long ticketId;

    private Long customerId;

    @Column(length = 32)
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Enums.NotifyChannel channel;

    @Column(nullable = false, length = 400)
    private String content;

    /** SENT / FAILED（演示环境恒为 SENT） */
    @Column(nullable = false, length = 8)
    private String status = "SENT";

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
