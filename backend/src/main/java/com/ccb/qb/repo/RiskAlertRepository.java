package com.ccb.qb.repo;

import com.ccb.qb.entity.RiskAlert;
import com.ccb.qb.model.Enums;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiskAlertRepository extends JpaRepository<RiskAlert, Long> {
    List<RiskAlert> findAllByOrderByCreatedAtDesc();
    List<RiskAlert> findByTicketNoOrderByCreatedAtAsc(String ticketNo);
    List<RiskAlert> findByStatusOrderByCreatedAtDesc(Enums.RiskAlertStatus status);
    long countByStatus(Enums.RiskAlertStatus status);
}
