package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备状态上报记录：reportKey 唯一约束保证同一上报重复提交只生效一次；
 * 设备状态反复上报（抖动）时仅在实际状态变化时应用，其余记为 NOOP。
 */
@Data
@Entity
@Table(name = "device_status_reports")
public class DeviceStatusReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long counterId;

    /** 上报幂等键（设备/调用方生成） */
    @Column(nullable = false, unique = true, length = 64)
    private String reportKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Enums.CounterStatus reportedStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private Enums.CounterStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Enums.DeviceReportResult result;

    @Column(length = 255)
    private String note;

    @Column(length = 32)
    private String actorName;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
