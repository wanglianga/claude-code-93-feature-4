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

    /** 排队容量：可同时挂在本台位的在途（已分配+办理中）单据上限 */
    private int capacity = 3;

    /** 可办理业务类型（逗号分隔 BusinessType；空表示全部可办） */
    @Column(length = 120)
    private String supportedBusiness;

    /** 乐观锁：并发迁移/容量锁定时防止互相覆盖 */
    @Version
    private Long version;

    @Column(length = 255)
    private String note;
}
