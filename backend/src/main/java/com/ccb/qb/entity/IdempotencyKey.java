package com.ccb.qb.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口幂等键：客户端对同一逻辑操作重复提交（双击/重试/刷新）时，
 * 服务端直接返回首次执行的响应，保证重复提交不产生重复单据或重复动作。
 */
@Data
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {
    @Id
    @Column(length = 80)
    private String idemKey;

    @Column(nullable = false, length = 64)
    private String endpoint;

    @Column(length = 4000)
    private String responseJson;

    private int statusCode;

    private LocalDateTime createdAt;
}
