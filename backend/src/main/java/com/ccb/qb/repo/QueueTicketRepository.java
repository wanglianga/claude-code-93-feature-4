package com.ccb.qb.repo;

import com.ccb.qb.entity.QueueTicket;
import com.ccb.qb.model.Enums;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface QueueTicketRepository extends JpaRepository<QueueTicket, Long> {
    List<QueueTicket> findAllByOrderByCreatedAtDesc();
    List<QueueTicket> findByStatusOrderByCreatedAtAsc(Enums.TicketStatus status);
    long countByStatus(Enums.TicketStatus status);
    long countByStatusAndElderlyTrue(Enums.TicketStatus status);
    List<QueueTicket> findByCounterIdAndStatusIn(Long counterId, List<Enums.TicketStatus> statuses);
    long countByCounterIdAndStatusIn(Long counterId, List<Enums.TicketStatus> statuses);
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    Optional<QueueTicket> findByTicketNo(String ticketNo);

    /** 悲观写锁：转台/迁移/叫号等并发路径先锁定单据 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from QueueTicket t where t.id = :id")
    Optional<QueueTicket> findByIdForUpdate(@Param("id") Long id);

    /** 重复取号检测：同一客户（按 customerId）在时间窗内的在途单据 */
    @Query("select t from QueueTicket t where t.customerId = :customerId and t.status in :statuses and t.createdAt > :since")
    List<QueueTicket> findActiveByCustomer(@Param("customerId") Long customerId,
                                           @Param("statuses") List<Enums.TicketStatus> statuses,
                                           @Param("since") LocalDateTime since);

    /** 重复取号检测（未登录现场客户按姓名） */
    @Query("select t from QueueTicket t where t.customerName = :name and t.status in :statuses and t.createdAt > :since")
    List<QueueTicket> findActiveByCustomerName(@Param("name") String name,
                                               @Param("statuses") List<Enums.TicketStatus> statuses,
                                               @Param("since") LocalDateTime since);

    @Query("select coalesce(sum(t.waitSeconds),0) from QueueTicket t where t.waitSeconds is not null and t.finishedAt between ?1 and ?2")
    long sumWaitSeconds(LocalDateTime from, LocalDateTime to);

    @Query("select count(t) from QueueTicket t where t.waitSeconds is not null and t.finishedAt between ?1 and ?2")
    long countFinished(LocalDateTime from, LocalDateTime to);

    long countByResultType(Enums.ResultType type);
    long countByComplaintTrue();
    long countByFraudStatus(Enums.FraudStatus status);
    long countByChannel(Enums.Channel channel);
}
