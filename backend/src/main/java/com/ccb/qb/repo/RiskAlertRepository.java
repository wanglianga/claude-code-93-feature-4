package com.ccb.qb.repo;

import com.ccb.qb.entity.RiskAlert;
import com.ccb.qb.model.Enums;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RiskAlertRepository extends JpaRepository<RiskAlert, Long> {
    List<RiskAlert> findAllByOrderByIdDesc();
    List<RiskAlert> findByStatusOrderByIdDesc(Enums.RiskAlertStatus status);
    List<RiskAlert> findByBizOrderNoOrderByIdAsc(String bizOrderNo);
}
