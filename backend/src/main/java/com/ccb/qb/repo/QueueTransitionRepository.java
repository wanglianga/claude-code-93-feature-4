package com.ccb.qb.repo;

import com.ccb.qb.entity.QueueTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QueueTransitionRepository extends JpaRepository<QueueTransition, Long> {
    List<QueueTransition> findByTicketNoOrderByCreatedAtAsc(String ticketNo);
    List<QueueTransition> findByTicketIdOrderByCreatedAtAsc(Long ticketId);
    List<QueueTransition> findAllByOrderByCreatedAtDesc();
}
