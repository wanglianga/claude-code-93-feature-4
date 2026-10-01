package com.ccb.qb.repo;

import com.ccb.qb.entity.DispositionAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DispositionActionRepository extends JpaRepository<DispositionAction, Long> {
    List<DispositionAction> findByDispositionIdOrderByCreatedAtAsc(Long dispositionId);
    List<DispositionAction> findByTicketNoOrderByCreatedAtAsc(String ticketNo);
    Optional<DispositionAction> findByIdempotencyKey(String idempotencyKey);
}
