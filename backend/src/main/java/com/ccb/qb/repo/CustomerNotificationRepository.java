package com.ccb.qb.repo;

import com.ccb.qb.entity.CustomerNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerNotificationRepository extends JpaRepository<CustomerNotification, Long> {
    List<CustomerNotification> findByTicketNoOrderByCreatedAtAsc(String ticketNo);
    List<CustomerNotification> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<CustomerNotification> findAllByOrderByCreatedAtDesc();
}
