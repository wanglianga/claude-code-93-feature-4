package com.ccb.qb.repo;

import com.ccb.qb.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findAllByOrderByIdDesc();
    List<Notification> findByBizOrderNoOrderByIdAsc(String bizOrderNo);
}
