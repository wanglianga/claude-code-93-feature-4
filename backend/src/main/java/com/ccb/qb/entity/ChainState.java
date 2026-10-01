package com.ccb.qb.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 处置单哈希链状态（单行）：生成处置单时以悲观锁串行化，
 * 保证链式哈希严格线性、单号单调递增，并发故障上报不会分叉。
 */
@Data
@Entity
@Table(name = "chain_state")
public class ChainState {
    @Id
    private Long id;

    @Column(nullable = false, length = 64)
    private String lastHash;

    /** 处置单序号（同时用于生成单号 CZ%06d） */
    private long seq;

    /** 维修单序号 */
    private long maintenanceSeq;

    /** 风控告警序号 */
    private long alertSeq;
}
