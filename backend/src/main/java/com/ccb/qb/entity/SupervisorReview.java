package com.ccb.qb.entity;

import com.ccb.qb.model.Enums;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 主管复核记录：对公平恢复建议的批准/驳回 */
@Data
@Entity
@Table(name = "supervisor_reviews")
public class SupervisorReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long suggestionId;

    @Column(nullable = false, length = 16)
    private String bizOrderNo;

    @Column(nullable = false)
    private Long reviewerId;

    @Column(nullable = false, length = 32)
    private String reviewerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Enums.ReviewDecision decision;

    @Column(length = 255)
    private String comment;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
