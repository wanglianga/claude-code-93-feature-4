package com.ccb.qb.web;

import com.ccb.qb.dto.Dtos;
import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.*;
import com.ccb.qb.security.LoginUser;
import com.ccb.qb.service.DispositionService;
import com.ccb.qb.service.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 台位异常处置（设备离线/转台失败）全部接口：
 * 设备上报（幂等）/ 处置单 / 前台处置 / 维修闭环 / 主管复核 / 风控告警 / 统一查询。
 */
@RestController
@RequestMapping("/api")
public class DispositionController {

    private final DispositionService svc;
    private final IdempotencyService idem;
    private final UserRepository users;
    private final DispositionOrderRepository dispositions;
    private final DispositionActionRepository actions;
    private final RepairOrderRepository repairs;
    private final DeviceEventRepository deviceEvents;
    private final RiskAlertRepository riskAlerts;
    private final CustomerNotificationRepository notifications;
    private final QueueTransitionRepository transitions;

    public DispositionController(DispositionService svc, IdempotencyService idem, UserRepository users,
                                 DispositionOrderRepository dispositions, DispositionActionRepository actions,
                                 RepairOrderRepository repairs, DeviceEventRepository deviceEvents,
                                 RiskAlertRepository riskAlerts, CustomerNotificationRepository notifications,
                                 QueueTransitionRepository transitions) {
        this.svc = svc;
        this.idem = idem;
        this.users = users;
        this.dispositions = dispositions;
        this.actions = actions;
        this.repairs = repairs;
        this.deviceEvents = deviceEvents;
        this.riskAlerts = riskAlerts;
        this.notifications = notifications;
        this.transitions = transitions;
    }

    private User actor(Authentication auth) {
        if (auth == null) return null;
        LoginUser p = (LoginUser) auth.getPrincipal();
        return users.findById(p.id()).orElse(null);
    }

    /* ---------- 设备状态上报（幂等） ---------- */

    @PostMapping("/devices/events")
    public ResponseEntity<?> deviceEvent(@Valid @RequestBody Dtos.DeviceEventRequest req,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         Authentication auth) {
        User u = actor(auth);
        return idem.execute(key, "POST /api/devices/events", () -> svc.reportDeviceEvent(req, u));
    }

    @GetMapping("/devices/events")
    public List<DeviceEvent> deviceEvents() {
        return deviceEvents.findAllByOrderByCreatedAtDesc();
    }

    /* ---------- 处置单 ---------- */

    @GetMapping("/dispositions")
    public List<DispositionOrder> dispositionList(@RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) return dispositions.findAllByOrderByCreatedAtDesc();
        return dispositions.findByStatusOrderByCreatedAtDesc(Enums.DispositionStatus.valueOf(status));
    }

    @GetMapping("/dispositions/{id}")
    public Map<String, Object> dispositionDetail(@PathVariable Long id) {
        DispositionOrder d = svc.mustDisposition(id);
        return Map.of("disposition", d, "actions", actions.findByDispositionIdOrderByCreatedAtAsc(id));
    }

    /** 哈希链校验：证明处置单快照不可篡改 */
    @GetMapping("/dispositions/verify-chain")
    public Map<String, Object> verifyChain() {
        return svc.verifyChain();
    }

    /** 前台处置：迁移 / 转人工 / 暂缓（幂等） */
    @PostMapping("/dispositions/{id}/actions")
    public ResponseEntity<?> applyAction(@PathVariable Long id,
                                         @Valid @RequestBody Dtos.DispositionActionRequest req,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         Authentication auth) {
        User u = actor(auth);
        return idem.execute(key, "POST /api/dispositions/" + id + "/actions",
                () -> svc.applyAction(id, req, key, u));
    }

    /** 主管复核恢复建议 */
    @PostMapping("/dispositions/{id}/review")
    public Map<String, Object> review(@PathVariable Long id, @Valid @RequestBody Dtos.ReviewRequest req,
                                      Authentication auth) {
        return svc.reviewRecovery(id, req, actor(auth));
    }

    /* ---------- 手动转台 / 暂缓恢复 ---------- */

    @PostMapping("/tickets/{id}/transfer")
    public Map<String, Object> transfer(@PathVariable Long id, @Valid @RequestBody Dtos.TransferRequest req,
                                        Authentication auth) {
        return svc.transferTicket(id, req, actor(auth));
    }

    @PostMapping("/tickets/{id}/resume")
    public QueueTicket resume(@PathVariable Long id, Authentication auth) {
        return svc.resumeTicket(id, actor(auth));
    }

    /* ---------- 设备维修 ---------- */

    @GetMapping("/repairs")
    public List<RepairOrder> repairList() {
        return repairs.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping("/repairs/{id}/start")
    public RepairOrder repairStart(@PathVariable Long id, Authentication auth) {
        return svc.startRepair(id, actor(auth));
    }

    @PostMapping("/repairs/{id}/complete")
    public ResponseEntity<?> repairComplete(@PathVariable Long id,
                                            @RequestBody(required = false) Dtos.NoteRequest req,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                            Authentication auth) {
        User u = actor(auth);
        String note = req == null ? null : req.note();
        return idem.execute(key, "POST /api/repairs/" + id + "/complete",
                () -> svc.completeRepair(id, note, u));
    }

    /* ---------- 风控告警 ---------- */

    @GetMapping("/risk-alerts")
    public List<RiskAlert> riskAlertList(@RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) return riskAlerts.findAllByOrderByCreatedAtDesc();
        return riskAlerts.findByStatusOrderByCreatedAtDesc(Enums.RiskAlertStatus.valueOf(status));
    }

    @PostMapping("/risk-alerts/{id}/ack")
    public RiskAlert ack(@PathVariable Long id, @RequestBody(required = false) Dtos.NoteRequest req,
                         Authentication auth) {
        return svc.ackAlert(id, req == null ? null : req.note(), actor(auth));
    }

    /* ---------- 客户通知 / 队列时间线 / 统一查询 ---------- */

    @GetMapping("/notifications")
    public List<CustomerNotification> notificationList(@RequestParam(required = false) String ticketNo,
                                                       @RequestParam(required = false) Long customerId) {
        if (ticketNo != null && !ticketNo.isBlank()) {
            return notifications.findByTicketNoOrderByCreatedAtAsc(ticketNo);
        }
        if (customerId != null) return notifications.findByCustomerIdOrderByCreatedAtDesc(customerId);
        return notifications.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/transitions")
    public List<QueueTransition> transitionList(@RequestParam(required = false) String ticketNo) {
        if (ticketNo != null && !ticketNo.isBlank()) return transitions.findByTicketNoOrderByCreatedAtAsc(ticketNo);
        return transitions.findAllByOrderByCreatedAtDesc();
    }

    /** 统一查询：同一业务单号的处置记录/维修闭环/客户通知/队列变更时间线/主管复核/风控告警 */
    @GetMapping("/orders/{ticketNo}/trace")
    public Map<String, Object> trace(@PathVariable String ticketNo) {
        return svc.trace(ticketNo);
    }
}
