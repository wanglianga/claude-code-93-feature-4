package com.ccb.qb.repo;

import com.ccb.qb.entity.DispositionOrder;
import com.ccb.qb.model.Enums;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DispositionOrderRepository extends JpaRepository<DispositionOrder, Long> {
    List<DispositionOrder> findAllByOrderByCreatedAtDesc();
    List<DispositionOrder> findByStatusOrderByCreatedAtDesc(Enums.DispositionStatus status);
    List<DispositionOrder> findByTicketNoOrderByCreatedAtAsc(String ticketNo);
    List<DispositionOrder> findByTicketIdOrderByCreatedAtAsc(Long ticketId);
    List<DispositionOrder> findByRepairId(Long repairId);
    List<DispositionOrder> findByTicketIdAndStatusIn(Long ticketId, List<Enums.DispositionStatus> statuses);
    Optional<DispositionOrder> findTopByOrderByIdDesc();
    boolean existsByOrderNo(String orderNo);
    long countByStatus(Enums.DispositionStatus status);
}
