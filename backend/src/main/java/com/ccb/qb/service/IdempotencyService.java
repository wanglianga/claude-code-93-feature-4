package com.ccb.qb.service;

import com.ccb.qb.entity.IdempotencyKey;
import com.ccb.qb.repo.IdempotencyKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 接口幂等：客户端在 Header 携带 Idempotency-Key 时，
 * 同一键的重复请求直接回放首次响应（HTTP 200 + X-Idempotent-Replay: true），
 * 保证重复提交/双击/网络重试不会产生重复单据或重复动作。
 */
@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository repo;
    private final ObjectMapper mapper;

    public IdempotencyService(IdempotencyKeyRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /**
     * 执行幂等包装。supplier 在事务内执行并返回业务对象；
     * 响应 JSON 与状态码落库，重复请求直接回放。
     */
    public ResponseEntity<?> execute(String key, String endpoint, Supplier<Object> supplier) {
        if (key == null || key.isBlank()) {
            return ResponseEntity.ok(supplier.get());
        }
        String scoped = endpoint + "::" + key.trim();
        var hit = repo.findById(scoped);
        if (hit.isPresent()) {
            return ResponseEntity.status(hit.get().getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Idempotent-Replay", "true")
                    .body(hit.get().getResponseJson());
        }
        Object result = supplier.get();
        try {
            IdempotencyKey k = new IdempotencyKey();
            k.setIdemKey(scoped);
            k.setEndpoint(endpoint);
            k.setResponseJson(mapper.writeValueAsString(result));
            k.setStatusCode(200);
            k.setCreatedAt(LocalDateTime.now());
            saveNew(k);
        } catch (Exception ignored) {
            // 序列化失败不影响主流程
        }
        return ResponseEntity.ok(result);
    }

    /** 独立事务落库，避免主事务回滚时幂等记录丢失/冲突 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveNew(IdempotencyKey k) {
        repo.save(k);
    }
}
