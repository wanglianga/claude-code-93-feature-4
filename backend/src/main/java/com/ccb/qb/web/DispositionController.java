package com.ccb.qb.web;

import com.ccb.qb.dto.Dtos;
import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.UserRepository;
import com.ccb.qb.security.LoginUser;
import com.ccb.qb.service.DispositionService;
import com.ccb.qb.service.RiskService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 特性4：设备离线/转台失败处置接口。
 * 前台（大堂经理）、柜台（柜员）、设备维修、风控、主管围绕同一业务单号协同。
 */
@RestController
@RequestMapping("/api")
public class DispositionController {

    private final DispositionService svc;
    private final RiskService riskService;
    private final UserRepository users;

    public DispositionController(DispositionService svc, RiskService riskService, UserRepository users) {
        this.svc = svc;
        this.riskService = riskService;
        this.users = users;
    }

    private User actor(Authentication auth) {
        if (auth == null) return null;
        LoginUser p = (LoginUser) auth.getPrincipal();
        return users.findById(p.id()).orElse(null);
    }

    /* ---------- 设备状态上报（幂等防抖动） ---------- */

    @PostMapping("/devices/{counterId}/status")
    public DeviceStatusReport reportDeviceStatus(@PathVariable Long counterId,
                                                 @Valid @RequestBody Dtos.DeviceStatusReportRequest req,
                                                 Authentication auth) {
        return svc.reportDeviceStatus(counterId, req.status(), req.reportKey(), req.note(), actor(auth));
    }

    /* ---------- 处置单（不可篡改） ---------- */

    @GetMapping("/dispositions")
    public List<DispositionOrder> dispositions(@RequestParam(required = false) Enums.DispositionStatus status) {
        return svc.listDispositions(status);
    }

    @GetMapping("/dispositions/{id}")
    public DispositionOrder disposition(@PathVariable Long id) {
        return svc.getDisposition(id);
    }

    @GetMapping("/dispositions/{id}/actions")
    public List<DispositionAction> dispositionActions(@PathVariable Long id) {
        return svc.listActions(id);
    }

    /** 哈希链完整性校验 */
    @GetMapping("/dispositions-verify-chain")
    public Map<String, Object> verifyChain() {
        return svc.verifyChain();
    }

    /* ---------- 前台三方案处置 ---------- */

    /** 方案一：保留原排队权重后迁移 */
    @PostMapping("/dispositions/{id}/migrate")
    public DispositionAction migrate(@PathVariable Long id,
                                     @Valid @RequestBody Dtos.MigrateRequest req,
                                     Authentication auth) {
        return svc.migrate(id, req.targetCounterId(), req.idempotencyKey(), actor(auth));
    }

    /** 方案二：转人工窗口 */
    @PostMapping("/dispositions/{id}/to-manual")
    public DispositionAction toManual(@PathVariable Long id,
                                      @Valid @RequestBody Dtos.MigrateRequest req,
                                      Authentication auth) {
        return svc.toManual(id, req.targetCounterId(), req.idempotencyKey(), actor(auth));
    }

    /** 方案三：客户暂缓 */
    @PostMapping("/dispositions/{id}/defer")
    public DispositionAction defer(@PathVariable Long id,
                                   @Valid @RequestBody Dtos.DeferRequest req,
                                   Authentication auth) {
        return svc.defer(id, req.note(), req.idempotencyKey(), actor(auth));
    }

    /** 直接转台尝试（转台失败时生成处置单） */
    @PostMapping("/tickets/{id}/transfer")
    public DispositionAction transfer(@PathVariable Long id,
                                      @Valid @RequestBody Dtos.TransferRequest req,
                                      Authentication auth) {
        return svc.transferNow(id, req.targetCounterId(), req.idempotencyKey(), actor(auth));
    }

    /* ---------- 设备维修闭环 ---------- */

    @GetMapping("/maintenance")
    public List<MaintenanceOrder> maintenance(@RequestParam(required = false) Enums.MaintenanceStatus status) {
        return svc.listMaintenance(status);
    }

    @PostMapping("/maintenance/{id}/start")
    public MaintenanceOrder startRepair(@PathVariable Long id, Authentication auth) {
        return svc.startRepair(id, actor(auth));
    }

    /** 维修完成：生成公平恢复建议（不自动插回队首） */
    @PostMapping("/maintenance/{id}/finish")
    public List<RecoverySuggestion> finishRepair(@PathVariable Long id, Authentication auth) {
        return svc.finishRepair(id, actor(auth));
    }

    /* ---------- 恢复建议与主管复核 ---------- */

    @GetMapping("/recovery-suggestions")
    public List<RecoverySuggestion> suggestions(@RequestParam(required = false) Enums.SuggestionStatus status) {
        return svc.listSuggestions(status);
    }

    @PostMapping("/recovery-suggestions/{id}/review")
    public SupervisorReview review(@PathVariable Long id,
                                   @Valid @RequestBody Dtos.ReviewRequest req,
                                   Authentication auth) {
        return svc.review(id, req.decision(), req.comment(), actor(auth));
    }

    /* ---------- 风控告警 ---------- */

    @GetMapping("/risk-alerts")
    public List<RiskAlert> riskAlerts(@RequestParam(required = false) Enums.RiskAlertStatus status) {
        return riskService.list(status);
    }

    @PostMapping("/risk-alerts/{id}/ack")
    public RiskAlert ackAlert(@PathVariable Long id, Authentication auth) {
        return riskService.acknowledge(id, actor(auth));
    }

    /** 人工插队调整（产生可追溯风控告警，不阻断已核验紧急业务） */
    @PostMapping("/tickets/{id}/jump")
    public RiskAlert jump(@PathVariable Long id, @RequestBody Dtos.JumpRequest req, Authentication auth) {
        return riskService.manualJump(id, req.reason(), actor(auth));
    }

    /* ---------- 统一查询 ---------- */

    /** 同一业务单号：处置单/处置记录/维修闭环/客户通知/队列变更时间线/主管复核/风控告警 */
    @GetMapping("/trace/{bizOrderNo}")
    public Map<String, Object> trace(@PathVariable String bizOrderNo) {
        return svc.trace(bizOrderNo);
    }
}
