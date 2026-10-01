package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

/** 服务窗口/柜台/自助机 */
@Data
@Entity
@Table(name = "counters")
public class Counter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Enums.CounterType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Enums.CounterStatus status = Enums.CounterStatus.OPEN;

    /** 当前柜员 */
    private Long tellerId;

    /** 当前办理的排队记录 */
    private Long currentTicketId;

    /** 今日已办件数 */
    private int servedCount;

    /** 平均办理秒数（用于负载展示） */
    private int avgServeSeconds;

    /** 台位容量：可同时容纳的排队中+办理中客户数（迁移时原子锁定） */
    private int capacity;

    /** 乐观锁：并发迁移锁定容量时防并发覆盖 */
    @Version
    private long version;

    @Column(length = 255)
    private String note;
}
