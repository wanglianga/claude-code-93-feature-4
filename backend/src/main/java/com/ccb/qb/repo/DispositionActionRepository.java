package com.ccb.qb.repo;

import com.ccb.qb.entity.DispositionAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DispositionActionRepository extends JpaRepository<DispositionAction, Long> {
    Optional<DispositionAction> findByIdempotencyKey(String idempotencyKey);
    List<DispositionAction> findByDispositionOrderIdOrderByIdAsc(Long dispositionOrderId);
    List<DispositionAction> findByBizOrderNoOrderByIdAsc(String bizOrderNo);
}
