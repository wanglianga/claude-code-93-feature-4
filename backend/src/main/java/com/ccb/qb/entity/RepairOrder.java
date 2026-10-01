package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 设备维修单：台位设备离线后由设备维修角色闭环 */
@Data
@Entity
@Table(name = "repair_orders")
public class RepairOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 维修单号 WXyyyyMMdd-序号 */
    @Column(nullable = false, unique = true, length = 24)
    private String repairNo;

    private Long counterId;

    @Column(length = 16)
    private String counterName;

    @Column(length = 255)
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Enums.RepairStatus status = Enums.RepairStatus.OPEN;

    @Column(length = 32)
    private String reportedBy;

    @Column(length = 300)
    private String resultNote;

    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
