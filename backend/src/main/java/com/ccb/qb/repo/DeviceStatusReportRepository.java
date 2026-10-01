package com.ccb.qb.repo;

import com.ccb.qb.entity.DeviceStatusReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceStatusReportRepository extends JpaRepository<DeviceStatusReport, Long> {
    Optional<DeviceStatusReport> findByReportKey(String reportKey);
}
