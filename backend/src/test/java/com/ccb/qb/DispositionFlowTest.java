package com.ccb.qb;

import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.service.DispositionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设备离线 → 不可篡改处置单 → 前台三方案处置 → 幂等与防抖动。
 */
class DispositionFlowTest extends TestSupport {

    @Autowired
    private DispositionService svc;

    @Test
    void offlineCreatesImmutableDispositionWithSnapshotAndHashChain() {
        User teller = user("teller01", Enums.Role.TELLER);
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        QueueTicket t = ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 300);

        DeviceStatusReport rec = svc.reportDeviceStatus(
                device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "网络中断", teller);

        assertEquals(Enums.DeviceReportResult.APPLIED, rec.getResult());
        assertEquals(Enums.CounterStatus.OFFLINE, reload(device).getStatus());

        // 票据冻结：处置中 + 权重快照 + 等待继承
        QueueTicket frozen = reload(t);
        assertEquals(Enums.TicketStatus.DISPOSING, frozen.getStatus());
        assertNotNull(frozen.getWeightSnapshot());
        assertTrue(frozen.getWeightSnapshot() >= 100 * 10_000L + 300);
        assertTrue(frozen.getInheritedWaitSeconds() >= 300);

        // 处置单：客户/队列/业务类型/优先级/原台位/已等待时长/失败原因 完整
        List<DispositionOrder> orders = dispositions.findAll();
        assertEquals(1, orders.size());
        DispositionOrder o = orders.get(0);
        assertEquals("A001", o.getBizOrderNo());
        assertEquals("张三", o.getCustomerName());
        assertEquals(Enums.BusinessType.ACCOUNT_OPEN, o.getBusinessType());
        assertEquals(100, o.getPriorityScore());
        assertEquals(device.getId(), o.getOriginalCounterId());
        assertTrue(o.getWaitedSeconds() >= 300);
        assertEquals(Enums.FailureType.DEVICE_OFFLINE, o.getFailureType());
        assertTrue(o.getFailureReason().contains("设备离线"));
        assertEquals("GENESIS", o.getPrevHash());
        assertEquals(DispositionService.computeHash(o), o.getHash());

        // 维修单 + 客户通知 + 时间线事件
        assertEquals(1, maintenance.count());
        assertEquals(1, notifications.count());
        assertTrue(events.findByTicketIdOrderByCreatedAtAsc(t.getId()).stream()
                .anyMatch(e -> e.getType() == Enums.IssueType.DEVICE_OFFLINE));

        // 哈希链校验通过
        Map<String, Object> chain = svc.verifyChain();
        assertEquals(Boolean.TRUE, chain.get("valid"));
    }

    @Test
    void flappingAndDuplicateReportsAreIdempotent() {
        User teller = user("teller01", Enums.Role.TELLER);
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        QueueTicket t = ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 60);

        svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "离线", teller);
        assertEquals(1, dispositions.count());
        assertEquals(1, maintenance.count());

        // 同一 reportKey 重复提交 → 幂等命中，不产生二次效果
        DeviceStatusReport dup = svc.reportDeviceStatus(
                device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "离线", teller);
        assertEquals(Enums.DeviceReportResult.APPLIED, dup.getResult());
        assertEquals(1, reports.count());
        assertEquals(1, dispositions.count());

        // 状态反复上报（抖动）：OFFLINE→OFFLINE 无变化 → NOOP
        DeviceStatusReport noop = svc.reportDeviceStatus(
                device.getId(), Enums.CounterStatus.OFFLINE, "rk-2", "重复离线", teller);
        assertEquals(Enums.DeviceReportResult.NOOP, noop.getResult());
        assertEquals(1, dispositions.count());
        assertEquals(1, maintenance.count());

        // 恢复开放 → 维修闭环 + 恢复建议
        DeviceStatusReport up = svc.reportDeviceStatus(
                device.getId(), Enums.CounterStatus.OPEN, "rk-3", "恢复", teller);
        assertEquals(Enums.DeviceReportResult.APPLIED, up.getResult());
        assertEquals(Enums.CounterStatus.OPEN, reload(device).getStatus());
        assertEquals(Enums.MaintenanceStatus.DONE,
                maintenance.findAll().get(0).getStatus());
        assertEquals(1, suggestions.count());

        // 再次离线：新维修单，但处置单不重复（仍有未闭环处置单）
        svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-4", "再次离线", teller);
        assertEquals(2, maintenance.count());
        assertEquals(1, dispositions.count());
        assertEquals(Enums.TicketStatus.DISPOSING, reload(t).getStatus());
    }

    @Test
    void migrateKeepsWeightInheritsWaitAndRecomputesEta() {
        User manager = user("manager", Enums.Role.MANAGER);
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        Counter target = counter("智能柜员机02", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 120);
        QueueTicket t = ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 240);
        // 原台位正在办理该客户（占用标记），迁移须原子释放
        device.setCurrentTicketId(t.getId());
        counters.save(device);

        svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "离线", null);
        DispositionOrder o = dispositions.findAll().get(0);

        DispositionAction a = svc.migrate(o.getId(), target.getId(), "idem-m1", manager);

        assertEquals(Enums.DispositionActionResult.SUCCESS, a.getResult());
        QueueTicket moved = reload(t);
        assertEquals(Enums.TicketStatus.ASSIGNED, moved.getStatus());
        assertEquals(target.getId(), moved.getCounterId());
        // 继承等待时长：故障前 240 秒不丢失
        assertTrue(moved.getInheritedWaitSeconds() >= 240);
        // 重算预计叫号时间
        assertNotNull(moved.getEstimatedCallAt());
        // 原台位占用已释放
        assertNull(reload(device).getCurrentTicketId());
        // 处置单已解决
        assertEquals(Enums.DispositionStatus.RESOLVED, dispositions.findById(o.getId()).orElseThrow().getStatus());
        // 时间线 + 通知
        assertTrue(events.findByTicketIdOrderByCreatedAtAsc(t.getId()).stream()
                .anyMatch(e -> e.getType() == Enums.IssueType.QUEUE_MIGRATED));
        assertTrue(notifications.count() >= 2);

        // 重复提交（同一幂等键）→ 返回同一动作，无双重效果
        DispositionAction again = svc.migrate(o.getId(), target.getId(), "idem-m1", manager);
        assertEquals(a.getId(), again.getId());
        assertEquals(1, actions.count());
        assertEquals(1, tickets.findByCounterIdAndStatusIn(target.getId(),
                List.of(Enums.TicketStatus.ASSIGNED)).size());
    }

    @Test
    void migrateRollsBackToExplainablePendingAssignWhenTargetFull() {
        User manager = user("manager", Enums.Role.MANAGER);
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        // 目标台位容量 1 且已被占满
        Counter target = counter("智能柜员机02", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 1, 180);
        ticket("A000", "占位客户", Enums.TicketStatus.ASSIGNED, target, 100, 10);
        QueueTicket t = ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 500);

        svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "离线", null);
        DispositionOrder o = dispositions.findAll().get(0);
        long snapshot = reload(t).getWeightSnapshot();

        DispositionAction a = svc.migrate(o.getId(), target.getId(), "idem-m1", manager);

        // 回滚到可解释的待分配状态
        assertEquals(Enums.DispositionActionResult.ROLLED_BACK, a.getResult());
        QueueTicket rolled = reload(t);
        assertEquals(Enums.TicketStatus.PENDING_ASSIGN, rolled.getStatus());
        assertNull(rolled.getCounterId());
        // 不丢失原队列位置：权重快照与继承等待完整保留
        assertEquals(snapshot, rolled.getWeightSnapshot());
        assertTrue(rolled.getInheritedWaitSeconds() >= 500);
        // 不重复排队：目标台位仍只有占位客户
        assertEquals(1, tickets.findByCounterIdAndStatusIn(target.getId(),
                List.of(Enums.TicketStatus.ASSIGNED)).size());
        // 处置单仍未闭环，时间线记录了可解释原因
        assertEquals(Enums.DispositionStatus.OPEN, dispositions.findById(o.getId()).orElseThrow().getStatus());
        assertTrue(events.findByTicketIdOrderByCreatedAtAsc(t.getId()).stream()
                .anyMatch(e -> e.getType() == Enums.IssueType.PENDING_ASSIGN));

        // 换一台有空位的台位重试 → 成功入队
        Counter target2 = counter("智能柜员机03", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        DispositionAction retry = svc.migrate(o.getId(), target2.getId(), "idem-m2", manager);
        assertEquals(Enums.DispositionActionResult.SUCCESS, retry.getResult());
        assertEquals(Enums.TicketStatus.ASSIGNED, reload(t).getStatus());
        assertTrue(reload(t).getInheritedWaitSeconds() >= 500);
    }

    @Test
    void deferFreezesWaitAndCanResumeLater() {
        User manager = user("manager", Enums.Role.MANAGER);
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        Counter target = counter("智能柜员机02", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        QueueTicket t = ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 120);

        svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "离线", null);
        DispositionOrder o = dispositions.findAll().get(0);

        DispositionAction d = svc.defer(o.getId(), "客户先去取快递", "idem-d1", manager);
        assertEquals(Enums.DispositionActionResult.SUCCESS, d.getResult());
        QueueTicket deferred = reload(t);
        assertEquals(Enums.TicketStatus.DEFERRED, deferred.getStatus());
        assertNull(deferred.getCounterId());
        assertTrue(deferred.getInheritedWaitSeconds() >= 120);

        // 重复提交暂缓 → 幂等
        DispositionAction dup = svc.defer(o.getId(), "客户先去取快递", "idem-d1", manager);
        assertEquals(d.getId(), dup.getId());
        assertEquals(1, actions.count());

        // 客户返回 → 从暂缓直接迁移，等待时长继续继承
        DispositionAction m = svc.migrate(o.getId(), target.getId(), "idem-m1", manager);
        assertEquals(Enums.DispositionActionResult.SUCCESS, m.getResult());
        assertEquals(Enums.TicketStatus.ASSIGNED, reload(t).getStatus());
        assertTrue(reload(t).getInheritedWaitSeconds() >= 120);
    }

    @Test
    void transferFailureCreatesDisposition() {
        User manager = user("manager", Enums.Role.MANAGER);
        Counter a = counter("1号综合柜台", Enums.CounterType.GENERAL, Enums.CounterStatus.OPEN, 8, 300);
        Counter b = counter("2号综合柜台", Enums.CounterType.GENERAL, Enums.CounterStatus.OFFLINE, 8, 300);
        QueueTicket t = ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, a, 100, 90);

        // 转台目标不可用 → 转台失败，生成处置单
        DispositionAction r = svc.transferNow(t.getId(), b.getId(), "idem-t1", manager);
        assertEquals(Enums.DispositionActionResult.ROLLED_BACK, r.getResult());
        assertEquals(Enums.TicketStatus.DISPOSING, reload(t).getStatus());
        assertEquals(1, dispositions.count());
        DispositionOrder o = dispositions.findAll().get(0);
        assertEquals(Enums.FailureType.TRANSFER_FAILED, o.getFailureType());
        assertTrue(o.getFailureReason().contains("转台失败"));

        // 转台到可用台位 → 直接成功，不产生新处置单
        Counter c = counter("3号综合柜台", Enums.CounterType.GENERAL, Enums.CounterStatus.OPEN, 8, 300);
        // 先让客户回到可转台状态（处置单走迁移流程即可，这里直接验证 transferNow 成功路径）
        QueueTicket t2 = ticket("A002", "李四", Enums.TicketStatus.ASSIGNED, a, 100, 30);
        DispositionAction ok = svc.transferNow(t2.getId(), c.getId(), "idem-t2", manager);
        assertEquals(Enums.DispositionActionResult.SUCCESS, ok.getResult());
        assertEquals(c.getId(), reload(t2).getCounterId());
        assertEquals(1, dispositions.count());
    }

    @Test
    void hashChainDetectsTampering() {
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 60);
        ticket("A002", "李四", Enums.TicketStatus.ASSIGNED, device, 300, 30);
        svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "离线", null);
        assertEquals(2, dispositions.count());
        assertEquals(Boolean.TRUE, svc.verifyChain().get("valid"));

        // 模拟篡改：直接改动已等待时长（绕过服务层）
        DispositionOrder o = dispositions.findAllByOrderByIdAsc().get(0);
        o.setWaitedSeconds(99999);
        dispositions.save(o);

        Map<String, Object> result = svc.verifyChain();
        assertEquals(Boolean.FALSE, result.get("valid"));
        assertTrue(((List<String>) result.get("brokenOrders")).contains(o.getOrderNo()));
    }

    @Test
    void unifiedTraceAggregatesAllCollaborationRecords() {
        User manager = user("manager", Enums.Role.MANAGER);
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        Counter target = counter("智能柜员机02", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        QueueTicket t = ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 60);

        svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "离线", null);
        DispositionOrder o = dispositions.findAll().get(0);
        svc.migrate(o.getId(), target.getId(), "idem-m1", manager);

        Map<String, Object> trace = svc.trace("A001");
        assertNotNull(trace.get("ticket"));
        assertEquals(1, ((List<?>) trace.get("dispositions")).size());
        assertEquals(1, ((List<?>) trace.get("actions")).size());
        assertEquals(1, ((List<?>) trace.get("maintenance")).size());
        assertTrue(((List<?>) trace.get("events")).size() >= 2);
        assertTrue(((List<?>) trace.get("notifications")).size() >= 2);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) trace.get("chain")).get("valid"));
    }
}
