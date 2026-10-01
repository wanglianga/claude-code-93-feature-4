package com.ccb.qb.web;

import com.ccb.qb.dto.Dtos;
import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.UserRepository;
import com.ccb.qb.security.LoginUser;
import com.ccb.qb.service.QueueService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 排队平台全部业务接口（六方协同）。
 * 演示系统不做细粒度方法级鉴权，登录即可调用，前端按角色呈现操作。
 */
@RestController
@RequestMapping("/api")
public class QueueController {

    private final QueueService svc;
    private final UserRepository users;
    private final com.ccb.qb.service.IdempotencyService idem;

    public QueueController(QueueService svc, UserRepository users,
                           com.ccb.qb.service.IdempotencyService idem) {
        this.svc = svc;
        this.users = users;
        this.idem = idem;
    }

    private User actor(Authentication auth) {
        if (auth == null) return null;
        LoginUser p = (LoginUser) auth.getPrincipal();
        return users.findById(p.id()).orElse(null);
    }

    /* ---------- 取号 / 预约 / 档案 ---------- */

    @PostMapping("/tickets")
    public org.springframework.http.ResponseEntity<?> create(
            @Valid @RequestBody Dtos.TicketRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            Authentication auth) {
        User u = actor(auth);
        return idem.execute(idemKey, "POST /api/tickets", () -> svc.createTicket(req, u));
    }

    @GetMapping("/tickets")
    public List<QueueTicket> tickets() {
        return svc.ticketList();
    }

    @GetMapping("/tickets/{id}")
    public QueueTicket detail(@PathVariable Long id) {
        return svc.getTicket(id);
    }

    @GetMapping("/tickets/{id}/timeline")
    public List<QueueEvent> timeline(@PathVariable Long id) {
        return svc.eventList(id);
    }

    /** 渠道智能推荐 */
    @GetMapping("/tickets/{id}/recommend")
    public Map<String, Object> recommend(@PathVariable Long id) {
        QueueTicket t = svc.ticketList().stream().filter(x -> x.getId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("记录不存在"));
        return svc.recommend(t);
    }

    @PostMapping("/tickets/{id}/assign")
    public QueueTicket assign(@PathVariable Long id, @RequestBody Dtos.AssignRequest req, Authentication auth) {
        return svc.assign(id, req, actor(auth));
    }

    @PostMapping("/tickets/{id}/call")
    public QueueTicket callTicket(@PathVariable Long id, Authentication auth) {
        return svc.callTicket(id, actor(auth));
    }

    @PostMapping("/tickets/{id}/complete")
    public QueueTicket complete(@PathVariable Long id, @Valid @RequestBody Dtos.CompleteRequest req,
                                Authentication auth) {
        return svc.complete(id, req, actor(auth));
    }

    @PostMapping("/tickets/{id}/cancel")
    public QueueTicket cancel(@PathVariable Long id, @RequestBody Map<String, String> body,
                              Authentication auth) {
        return svc.cancel(id, body.getOrDefault("reason", "客户取消"), actor(auth));
    }

    @PostMapping("/tickets/{id}/fraud")
    public QueueTicket fraud(@PathVariable Long id, @Valid @RequestBody Dtos.FraudRequest req,
                             Authentication auth) {
        return svc.fraudReview(id, req, actor(auth));
    }

    @PostMapping("/tickets/{id}/callback")
    public QueueTicket callback(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return svc.callback(id, body.get("callbackNote"));
    }

    /* ---------- 柜员叫号 ---------- */

    @PostMapping("/counters/{id}/call-next")
    public QueueTicket callNext(@PathVariable Long id, Authentication auth) {
        return svc.callNext(id, actor(auth));
    }

    @GetMapping("/counters")
    public List<Counter> counters() {
        return svc.counterList();
    }

    @GetMapping("/counters/{id}")
    public Counter counter(@PathVariable Long id) {
        return svc.counterList().stream().filter(c -> c.getId().equals(id)).findFirst()
                .orElseThrow(() -> new com.ccb.qb.service.BizException("窗口不存在: " + id));
    }

    @PostMapping("/counters/{id}/status")
    public Counter counterStatus(@PathVariable Long id, @Valid @RequestBody Dtos.CounterStatusRequest req,
                                 Authentication auth) {
        return svc.updateCounterStatus(id, req, actor(auth));
    }

    /* ---------- 协同事件 ---------- */

    @PostMapping("/events")
    public QueueEvent reportEvent(@Valid @RequestBody Dtos.EventRequest req, Authentication auth) {
        return svc.reportEvent(req, actor(auth));
    }

    @GetMapping("/events")
    public List<QueueEvent> events(@RequestParam(required = false) Long ticketId) {
        return svc.eventList(ticketId);
    }

    @PostMapping("/events/{id}/resolve")
    public QueueEvent resolve(@PathVariable Long id, @Valid @RequestBody Dtos.EventResolveRequest req,
                              Authentication auth) {
        return svc.resolveEvent(id, req, actor(auth));
    }

    /* ---------- 现金库存 / 网点状态 ---------- */

    @GetMapping("/cash")
    public CashInventory cash() {
        return svc.todayCash();
    }

    @PutMapping("/cash")
    public CashInventory updateCash(@RequestBody Dtos.CashUpdateRequest req) {
        return svc.updateCash(req);
    }

    @PostMapping("/branch/outage")
    public BranchAlert outage(@RequestBody Map<String, Object> body, Authentication auth) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return svc.toggleOutage(active, (String) body.getOrDefault("note", ""), actor(auth));
    }

    @PostMapping("/branch/pension-day")
    public BranchAlert pension(@RequestBody Map<String, Object> body) {
        boolean active = Boolean.TRUE.equals(body.get("active"));
        return svc.pensionDay(active, (String) body.get("note"));
    }

    @GetMapping("/branch/alerts")
    public List<BranchAlert> alerts() {
        return svc.activeAlerts();
    }

    @GetMapping("/staff")
    public List<User> staff() {
        return svc.staffList();
    }

    /* ---------- 大堂看板 / 运营档案 ---------- */

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return svc.dashboard();
    }
}
