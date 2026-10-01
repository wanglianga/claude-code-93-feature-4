package com.ccb.qb.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备状态上报记录。eventId 全局唯一：
 * 设备侧反复上报同一事件（网络重试/状态抖动）只会处理一次，
 * 重复上报直接返回首次处理结果，不会重复生成处置单或造成队列丢失。
 */
@Data
@Entity
@Table(name = "device_events")
public class DeviceEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 设备上报幂等键（设备厂商/终端编号 + 事件序号） */
    @Column(nullable = false, unique = true, length = 64)
    private String eventId;

    private Long counterId;

    /** ONLINE / OFFLINE */
    @Column(nullable = false, length = 12)
    private String status;

    @Column(length = 255)
    private String reason;

    @Column(length = 32)
    private String reportedBy;

    /** 本次上报是否真正触发了状态处理（重复上报/状态未变为 false） */
    private boolean processed;

    @Column(length = 300)
    private String resultNote;

    private LocalDateTime createdAt;
}
