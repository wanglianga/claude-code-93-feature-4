package com.ccb.qb;

import com.ccb.qb.dto.Dtos;
import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.service.BizException;
import com.ccb.qb.service.DispositionService;
import com.ccb.qb.service.QueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发与幂等：并发转台不双重入队/不丢队列、重复提交只生效一次、并发叫号不重复。
 */
class ConcurrencyTest extends TestSupport {

    @Autowired
    private DispositionService svc;
    @Autowired
    private QueueService queueService;

    @Test
    void concurrentMigrateToSingleCapacityCounterExactlyOneSucceeds() throws Exception {
        User manager = user("manager", Enums.Role.MANAGER);
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        // 目标台位容量仅 1
        Counter target = counter("智能柜员机02", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 1, 180);
        QueueTicket t1 = ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 300);
        QueueTicket t2 = ticket("A002", "李四", Enums.TicketStatus.ASSIGNED, device, 100, 200);

        svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "离线", null);
        List<DispositionOrder> orders = dispositions.findAllByOrderByIdAsc();
        assertEquals(2, orders.size());

        // 两个前台同时把两张票迁往同一台位
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<DispositionAction> m1 = () -> {
            ready.countDown(); go.await();
            return svc.migrate(orders.get(0).getId(), target.getId(), "idem-c1", manager);
        };
        Callable<DispositionAction> m2 = () -> {
            ready.countDown(); go.await();
            return svc.migrate(orders.get(1).getId(), target.getId(), "idem-c2", manager);
        };
        Future<DispositionAction> f1 = pool.submit(m1);
        Future<DispositionAction> f2 = pool.submit(m2);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        List<DispositionAction> results = List.of(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS));
        pool.shutdown();

        long success = results.stream().filter(a -> a.getResult() == Enums.DispositionActionResult.SUCCESS).count();
        long rolledBack = results.stream().filter(a -> a.getResult() == Enums.DispositionActionResult.ROLLED_BACK).count();
        assertEquals(1, success, "容量1的台位只能成功迁移一张票");
        assertEquals(1, rolledBack, "另一张票必须回滚到待分配");

        // 不双重排队：目标台位只有一张 ASSIGNED
        assertEquals(1, tickets.findByCounterIdAndStatusIn(target.getId(),
                List.of(Enums.TicketStatus.ASSIGNED)).size());
        // 不丢队列：两张票一张入队、一张待分配，权重快照都在
        QueueTicket r1 = reload(t1), r2 = reload(t2);
        Set<Enums.TicketStatus> states = EnumSet.of(r1.getStatus(), r2.getStatus());
        assertTrue(states.contains(Enums.TicketStatus.ASSIGNED));
        assertTrue(states.contains(Enums.TicketStatus.PENDING_ASSIGN));
        assertNotNull(r1.getWeightSnapshot());
        assertNotNull(r2.getWeightSnapshot());
    }

    @Test
    void concurrentDuplicateSubmitSameIdempotencyKeyOnlyOneAction() throws Exception {
        User manager = user("manager", Enums.Role.MANAGER);
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        Counter target = counter("智能柜员机02", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 100);
        svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-1", "离线", null);
        DispositionOrder o = dispositions.findAll().get(0);

        // 同一处置动作被并发重复提交 4 次（同一幂等键）
        int n = 4;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<DispositionAction>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return svc.migrate(o.getId(), target.getId(), "idem-same", manager);
            }));
        }
        go.countDown();
        Set<Long> actionIds = new HashSet<>();
        for (Future<DispositionAction> f : futures) {
            actionIds.add(f.get(10, TimeUnit.SECONDS).getId());
        }
        pool.shutdown();

        assertEquals(1, actionIds.size(), "重复提交必须收敛到同一处置动作");
        assertEquals(1, actions.count());
        assertEquals(1, tickets.findByCounterIdAndStatusIn(target.getId(),
                List.of(Enums.TicketStatus.ASSIGNED)).size());
    }

    @Test
    void duplicateTicketCreationReturnsSameTicket() {
        User customer = user("customer01", Enums.Role.CUSTOMER);
        Dtos.TicketRequest req = new Dtos.TicketRequest(
                null, "王五", Enums.CustomerType.NORMAL, Enums.Source.MOBILE_APP,
                Enums.BusinessType.ACCOUNT_OPEN, Enums.RiskLevel.LOW,
                false, false, false, null, null, null,
                false, false, false, false, null,
                "idem-ticket-1", false, true);
        QueueTicket a = queueService.createTicket(req, customer);
        QueueTicket b = queueService.createTicket(req, customer);
        assertEquals(a.getId(), b.getId());
        assertEquals(1, tickets.count());
    }

    @Test
    void concurrentCallNextNeverCallsSameTicketTwice() throws Exception {
        User teller = user("teller01", Enums.Role.TELLER);
        Counter c = counter("1号综合柜台", Enums.CounterType.GENERAL, Enums.CounterStatus.OPEN, 8, 300);
        ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, c, 100, 50);
        ticket("A002", "李四", Enums.TicketStatus.ASSIGNED, c, 100, 40);

        // 两个柜员同时在同一窗口叫号：两张票各被叫一次，绝不重复
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Long> call = () -> {
            go.await();
            return queueService.callNext(c.getId(), teller).getId();
        };
        Future<Long> f1 = pool.submit(call);
        Future<Long> f2 = pool.submit(call);
        go.countDown();
        Set<Long> called = Set.of(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(2, called.size(), "同一张票不得被叫号两次");

        // 队列已空：第三次叫号报“暂无可叫号码”
        assertThrows(BizException.class, () -> queueService.callNext(c.getId(), teller));
    }

    @Test
    void concurrentOfflineReportsCreateSingleDispositionPerTicket() throws Exception {
        Counter device = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, Enums.CounterStatus.OPEN, 4, 180);
        ticket("A001", "张三", Enums.TicketStatus.ASSIGNED, device, 100, 100);

        // 设备监控与人工同时上报离线（不同 reportKey）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<DeviceStatusReport> r1 = () -> {
            go.await();
            return svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-a", "监控离线", null);
        };
        Callable<DeviceStatusReport> r2 = () -> {
            go.await();
            return svc.reportDeviceStatus(device.getId(), Enums.CounterStatus.OFFLINE, "rk-b", "人工离线", null);
        };
        Future<DeviceStatusReport> f1 = pool.submit(r1);
        Future<DeviceStatusReport> f2 = pool.submit(r2);
        go.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // 一个 APPLIED 一个 NOOP；处置单与维修单各一张
        assertEquals(1, dispositions.count());
        assertEquals(1, maintenance.count());
        assertEquals(Enums.TicketStatus.DISPOSING,
                tickets.findAll().get(0).getStatus());
    }
}
