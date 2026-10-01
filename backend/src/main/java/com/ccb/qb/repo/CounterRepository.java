package com.ccb.qb.repo;

import com.ccb.qb.entity.Counter;
import com.ccb.qb.model.Enums;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CounterRepository extends JpaRepository<Counter, Long> {
    List<Counter> findByStatus(Enums.CounterStatus status);

    /**
     * 悲观写锁：迁移锁定容量 / 叫号防重 / 设备状态变更时串行化同台位并发操作。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Counter c where c.id = ?1")
    Optional<Counter> findForUpdate(Long id);
}
