package com.ccb.qb.repo;

import com.ccb.qb.entity.DispositionOrder;
import com.ccb.qb.model.Enums;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DispositionOrderRepository extends JpaRepository<DispositionOrder, Long> {
    List<DispositionOrder> findAllByOrderByIdDesc();
    List<DispositionOrder> findAllByOrderByIdAsc();
    List<DispositionOrder> findByStatusOrderByIdDesc(Enums.DispositionStatus status);
    List<DispositionOrder> findByBizOrderNoOrderByIdAsc(String bizOrderNo);
    List<DispositionOrder> findByTicketIdOrderByIdDesc(Long ticketId);
    List<DispositionOrder> findByMaintenanceOrderIdOrderByIdAsc(Long maintenanceOrderId);
    boolean existsByTicketIdAndMaintenanceOrderId(Long ticketId, Long maintenanceOrderId);
    Optional<DispositionOrder> findFirstByTicketIdAndStatusOrderByIdDesc(Long ticketId, Enums.DispositionStatus status);

    /** 悲观写锁：处置动作串行化（与幂等检查配合，防并发重复提交产生双重效果） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from DispositionOrder o where o.id = ?1")
    Optional<DispositionOrder> findForUpdate(Long id);
}
