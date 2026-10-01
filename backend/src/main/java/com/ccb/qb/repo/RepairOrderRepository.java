package com.ccb.qb.repo;

import com.ccb.qb.entity.RepairOrder;
import com.ccb.qb.model.Enums;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RepairOrderRepository extends JpaRepository<RepairOrder, Long> {
    List<RepairOrder> findAllByOrderByCreatedAtDesc();
    List<RepairOrder> findByStatusOrderByCreatedAtDesc(Enums.RepairStatus status);
    Optional<RepairOrder> findFirstByCounterIdAndStatusInOrderByCreatedAtDesc(
            Long counterId, List<Enums.RepairStatus> statuses);
}
