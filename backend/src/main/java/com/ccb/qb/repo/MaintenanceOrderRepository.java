package com.ccb.qb.repo;

import com.ccb.qb.entity.MaintenanceOrder;
import com.ccb.qb.model.Enums;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaintenanceOrderRepository extends JpaRepository<MaintenanceOrder, Long> {
    List<MaintenanceOrder> findAllByOrderByIdDesc();
    List<MaintenanceOrder> findByStatusOrderByIdDesc(Enums.MaintenanceStatus status);
    Optional<MaintenanceOrder> findFirstByCounterIdAndStatusInOrderByIdDesc(
            Long counterId, List<Enums.MaintenanceStatus> statuses);
    List<MaintenanceOrder> findByCounterIdOrderByIdDesc(Long counterId);
}
