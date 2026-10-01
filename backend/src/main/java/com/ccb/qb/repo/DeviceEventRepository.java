package com.ccb.qb.repo;

import com.ccb.qb.entity.DeviceEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceEventRepository extends JpaRepository<DeviceEvent, Long> {
    Optional<DeviceEvent> findByEventId(String eventId);
    List<DeviceEvent> findAllByOrderByCreatedAtDesc();
}
