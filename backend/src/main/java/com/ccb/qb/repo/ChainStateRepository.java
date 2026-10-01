package com.ccb.qb.repo;

import com.ccb.qb.entity.ChainState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ChainStateRepository extends JpaRepository<ChainState, Long> {
    /** 悲观锁串行化处置单/维修单/告警单号的生成与哈希链推进 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChainState c where c.id = 1")
    Optional<ChainState> findForUpdate();
}
