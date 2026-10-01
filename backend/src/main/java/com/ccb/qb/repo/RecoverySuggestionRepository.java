package com.ccb.qb.repo;

import com.ccb.qb.entity.RecoverySuggestion;
import com.ccb.qb.model.Enums;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecoverySuggestionRepository extends JpaRepository<RecoverySuggestion, Long> {
    List<RecoverySuggestion> findAllByOrderByIdDesc();
    List<RecoverySuggestion> findByStatusOrderByIdDesc(Enums.SuggestionStatus status);
    List<RecoverySuggestion> findByBizOrderNoOrderByIdAsc(String bizOrderNo);
    List<RecoverySuggestion> findByMaintenanceOrderIdOrderByIdAsc(Long maintenanceOrderId);
    boolean existsByTicketIdAndStatus(Long ticketId, Enums.SuggestionStatus status);
}
