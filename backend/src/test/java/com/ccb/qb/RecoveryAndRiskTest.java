package com.ccb.qb;

import com.ccb.qb.dto.Dtos;
import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.service.DispositionService;
import com.ccb.qb.service.QueueService;
import com.ccb.qb.service.RiskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 维修闭环 → 公平恢复建议（不自动插回队首）→ 主管复核；风控告警不阻断已核验紧急业务。
 */
class RecoveryAndRiskTest extends TestSupport {

    @Autowired
    private DispositionService svc;
    @Autowired
    private QueueService queueService;
    @Autowired
    private RiskService riskService;

    /** 维修完成后：按权重快照给出公平恢复建议，主管批准后按排位恢复（不是队首） */
    @Test
    void fairRecoverySuggestionNotQueueHeadAndSupervisorApprove() {
        User repair = user("repair01", Enums.Role.MAINTENANCE);
        User supervisor = user("supervisor", Enums.Role.SUPERVISOR);
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 8, 180);
        // 故障客户：普通优先级但已等待 5000 秒 → 快照权重 1,005,000
        QueueTicket t1 = ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 5000);

        svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "离线", null);
        MaintenanceOrder mo = maintenance.findAll().get(0);

        // 设备离线期间，新到客户被分流到该台位（排队继续）
        ticket("A002", "VIP客户", Enums.TicketStatus.ASSIGNED, device, 300, 5);   // 权重 3,000,005
        ticket("A003", "普通客户", Enums.TicketStatus.ASSIGNED, device, 100, 10);  // 权重 1,000,010

        // 维修完成 → 生成恢复建议
        svc.startRepair(mo.getId(), repair);
        List<RecoverySuggestion> list = svc.finishRepair(mo.getId(), repair);
        assertEquals(1, list.size());
        RecoverySuggestion s = list.get(0);

        // 不自动插回队首：VIP 权重更高排在前，故障客户按快照排第 2
        assertEquals(device.getId(), s.getSuggestedCounterId());
        assertEquals(2, s.getSuggestedPosition());
        assertEquals(Enums.SuggestionStatus.PENDING, s.getStatus());
        assertTrue(s.getReasonJson().contains("weightSnapshot"));
        // 客户仍处于待安置状态（未自动入队）
        assertEquals(Enums.TicketStatus.DISPOSING, reload(t1).getStatus());

        // 主管复核通过 → 按建议恢复
        svc.review(s.getId(), Enums.ReviewDecision.APPROVE, "同意按建议恢复", supervisor);
        QueueTicket restored = reload(t1);
        assertEquals(Enums.TicketStatus.ASSIGNED, restored.getStatus());
        assertEquals(device.getId(), restored.getCounterId());
        assertNotNull(restored.getEstimatedCallAt());
        assertEquals(Enums.SuggestionStatus.APPLIED, suggestions.findById(s.getId()).orElseThrow().getStatus());
        assertEquals(Enums.DispositionStatus.RECOVERED,
                dispositions.findAll().get(0).getStatus());
        assertEquals(1, reviews.count());
        assertTrue(events.findByTicketIdOrderByCreatedAtAsc(t1.getId()).stream()
                .anyMatch(e -> e.getType() == Enums.IssueType.RECOVERY_APPLIED));

        // 重复复核 → 幂等返回首次结果
        svc.review(s.getId(), Enums.ReviewDecision.APPROVE, "重复点击", supervisor);
        assertEquals(1, reviews.count());
    }

    /** 主管驳回 → 转入待分配，由前台重新处置 */
    @Test
    void supervisorRejectFallsBackToPendingAssign() {
        User supervisor = user("supervisor", Enums.Role.SUPERVISOR);
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 8, 180);
        QueueTicket t1 = ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 500);

        svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "离线", null);
        MaintenanceOrder mo = maintenance.findAll().get(0);
        List<RecoverySuggestion> list = svc.finishRepair(mo.getId(), null);
        assertEquals(1, list.size());

        svc.review(list.get(0).getId(), Enums.ReviewDecision.REJECT, "该客户已由前台人工安置", supervisor);
        assertEquals(Enums.TicketStatus.PENDING_ASSIGN, reload(t1).getStatus());
        assertEquals(Enums.SuggestionStatus.REJECTED,
                suggestions.findById(list.get(0).getId()).orElseThrow().getStatus());
    }

    /** 重复取号：未核验客户达到阈值 → 告警并挂起（阻断）；风控确认后放行 */
    @Test
    void repeatTicketAlertBlocksUnverifiedCustomer() {
        User customer = user("customer01", Enums.Role.CUSTOMER);
        User risk = user("risk01", Enums.Role.RISK);
        Dtos.TicketRequest req = new Dtos.TicketRequest(
                null, "反复取号人", Enums.CustomerType.NORMAL, Enums.Source.WALK_IN,
                Enums.BusinessType.TRANSFER, Enums.RiskLevel.LOW,
                false, false, false, null, null, null,
                false, false, false, false, null,
                null, false, false);

        queueService.createTicket(req, customer);
        queueService.createTicket(req, customer);
        QueueTicket third = queueService.createTicket(req, customer);

        // 第三次取号触发告警：未核验 → 阻断（挂起）
        assertEquals(1, riskAlerts.count());
        RiskAlert alert = riskAlerts.findAll().get(0);
        assertEquals(Enums.RiskAlertType.REPEAT_TICKET, alert.getAlertType());
        assertTrue(alert.isBlocking());
        assertNotNull(alert.getTraceRef());
        assertEquals(Enums.TicketStatus.PENDING, reload(third).getStatus());

        // 风控确认 → 放行
        riskService.acknowledge(alert.getId(), risk);
        assertEquals(Enums.TicketStatus.WAITING, reload(third).getStatus());
        assertEquals(Enums.RiskAlertStatus.ACKNOWLEDGED, riskAlerts.findAll().get(0).getStatus());
    }

    /** 告警不能阻断已完成身份核验的紧急业务 */
    @Test
    void repeatTicketAlertNeverBlocksVerifiedUrgentBusiness() {
        User customer = user("customer01", Enums.Role.CUSTOMER);
        Dtos.TicketRequest urgentVerified = new Dtos.TicketRequest(
                null, "紧急客户", Enums.CustomerType.VIP, Enums.Source.WALK_IN,
                Enums.BusinessType.LARGE_CASH, Enums.RiskLevel.HIGH,
                false, false, false, null, null, 200_000L,
                false, false, false, false, null,
                null, true, true); // 紧急 + 身份已核验

        queueService.createTicket(urgentVerified, customer);
        queueService.createTicket(urgentVerified, customer);
        QueueTicket third = queueService.createTicket(urgentVerified, customer);

        // 告警照常生成（可追溯），但不阻断：单据正常排队
        assertEquals(1, riskAlerts.count());
        RiskAlert alert = riskAlerts.findAll().get(0);
        assertFalse(alert.isBlocking());
        assertEquals(Enums.TicketStatus.WAITING, reload(third).getStatus());
    }

    /** 人工插队产生可追溯告警；频繁转台触发风控 */
    @Test
    void manualJumpAndFrequentTransferAlerts() {
        User manager = user("manager", Enums.Role.MANAGER);
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        Counter t1 = counter("智能柜员机02", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        Counter t2 = counter("智能柜员机03", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        QueueTicket tk = ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 60);

        // 人工插队：优先级分提升 + 告警
        int before = reload(tk).getPriorityScore();
        RiskAlert jump = riskService.manualJump(tk.getId(), "客户声称有急事", manager);
        assertEquals(Enums.RiskAlertType.MANUAL_JUMP, jump.getAlertType());
        assertEquals(before + 300, reload(tk).getPriorityScore());
        assertNotNull(jump.getTraceRef());

        // 设备离线 → 连续两次迁移 → 频繁转台告警
        svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "离线", null);
        DispositionOrder o = dispositions.findAll().get(0);
        svc.migrate(o.getId(), t1.getId(), "idem-m1", manager);   // transferCount = 1
        // 第二台也离线 → 再次迁移
        svc.reportDeviceStatus(t1.getId(), Enums.CounterStatus.OFFLINE, "rk-2", "离线", null);
        DispositionOrder o2 = dispositions.findFirstByTicketIdAndStatusOrderByIdDesc(
                tk.getId(), Enums.DispositionStatus.OPEN).orElseThrow();
        svc.migrate(o2.getId(), t2.getId(), "idem-m2", manager);  // transferCount = 2 → 告警

        assertTrue(riskAlerts.findAll().stream()
                .anyMatch(a -> a.getAlertType() == Enums.RiskAlertType.FREQUENT_TRANSFER));
        assertEquals(2, reload(tk).getTransferCount());
    }
}
