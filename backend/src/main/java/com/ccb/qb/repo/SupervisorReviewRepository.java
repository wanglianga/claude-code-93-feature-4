package com.ccb.qb.repo;

import com.ccb.qb.entity.SupervisorReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupervisorReviewRepository extends JpaRepository<SupervisorReview, Long> {
    List<SupervisorReview> findBySuggestionIdOrderByIdDesc(Long suggestionId);
    List<SupervisorReview> findByBizOrderNoOrderByIdAsc(String bizOrderNo);
}
