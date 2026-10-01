package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备维修单：台位离线/故障时自动生成（同一台位同一时段仅一张未闭环维修单），
 * 维修完成后触发公平恢复建议，构成维修闭环。
 */
@Data
@Entity
@Table(name = "maintenance_orders")
public class MaintenanceOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 维修单号，如 WX000001 */
    @Column(nullable = false, unique = true, length = 16)
    private String orderNo;

    @Column(nullable = false)
    private Long counterId;

    @Column(nullable = false, length = 16)
    private String counterName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Enums.CounterStatus failureStatus;

    @Column(length = 255)
    private String faultDesc;

    @Column(length = 32)
    private String reportedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Enums.MaintenanceStatus status = Enums.MaintenanceStatus.OPEN;

    private Long assigneeId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
