package com.ccb.qb.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 客户通知：处置、迁移、暂缓、恢复等关键节点推送给客户（演示环境落库模拟短信/App 推送） */
@Data
@Entity
@Table(name = "customer_notifications")
public class CustomerNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long ticketId;

    @Column(length = 16)
    private String ticketNo;

    private Long customerId;

    /** APP / SMS / SPEAKER */
    @Column(nullable = false, length = 12)
    private String channel;

    @Column(nullable = false, length = 300)
    private String content;

    private LocalDateTime createdAt;
}
