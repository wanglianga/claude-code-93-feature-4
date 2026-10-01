package com.ccb.qb.repo;

import com.ccb.qb.entity.QueueTicket;
import com.ccb.qb.model.Enums;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface QueueTicketRepository extends JpaRepository<QueueTicket, Long> {
    List<QueueTicket> findAllByOrderByCreatedAtDesc();
    List<QueueTicket> findByStatusOrderByCreatedAtAsc(Enums.TicketStatus status);
    long countByStatus(Enums.TicketStatus status);
    long countByStatusAndElderlyTrue(Enums.TicketStatus status);
    List<QueueTicket> findByCounterIdAndStatusIn(Long counterId, List<Enums.TicketStatus> statuses);
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    @Query("select coalesce(sum(t.waitSeconds),0) from QueueTicket t where t.waitSeconds is not null and t.finishedAt between ?1 and ?2")
    long sumWaitSeconds(LocalDateTime from, LocalDateTime to);

    @Query("select count(t) from QueueTicket t where t.waitSeconds is not null and t.finishedAt between ?1 and ?2")
    long countFinished(LocalDateTime from, LocalDateTime to);

    long countByResultType(Enums.ResultType type);
    long countByComplaintTrue();
    long countByFraudStatus(Enums.FraudStatus status);
    long countByChannel(Enums.Channel channel);

    /* ---------- 特性4 ---------- */

    /** 取号幂等：同一幂等键重复提交返回同一单据 */
    Optional<QueueTicket> findByIdempotencyKey(String idempotencyKey);

    /** 台位当前占用（排队中+办理中），用于容量锁定判断 */
    long countByCounterIdAndStatusIn(Long counterId, List<Enums.TicketStatus> statuses);

    /** 同一客户（按客户ID）在时间窗内的有效单据，重复取号风控 */
    @Query("select t from QueueTicket t where t.customerId = ?1 and t.createdAt > ?2 and t.status <> 'DONE' and t.status <> 'CANCELLED'")
    List<QueueTicket> findActiveByCustomerSince(Long customerId, LocalDateTime since);

    /** 同一客户（按姓名，现场未登录客户）在时间窗内的有效单据 */
    @Query("select t from QueueTicket t where t.customerId is null and t.customerName = ?1 and t.createdAt > ?2 and t.status <> 'DONE' and t.status <> 'CANCELLED'")
    List<QueueTicket> findActiveByCustomerNameSince(String customerName, LocalDateTime since);

    List<QueueTicket> findByTicketNo(String ticketNo);
}
