package com.ccb.qb;

import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

/** 测试基座：H2 内存库、每用例清库、通用造数工具 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class TestSupport {

    @Autowired protected QueueTicketRepository tickets;
    @Autowired protected CounterRepository counters;
    @Autowired protected QueueEventRepository events;
    @Autowired protected UserRepository users;
    @Autowired protected DispositionOrderRepository dispositions;
    @Autowired protected DispositionActionRepository actions;
    @Autowired protected MaintenanceOrderRepository maintenance;
    @Autowired protected RecoverySuggestionRepository suggestions;
    @Autowired protected SupervisorReviewRepository reviews;
    @Autowired protected DeviceStatusReportRepository reports;
    @Autowired protected RiskAlertRepository riskAlerts;
    @Autowired protected NotificationRepository notifications;
    @Autowired protected ChainStateRepository chain;

    @BeforeEach
    void cleanDb() {
        actions.deleteAll();
        reviews.deleteAll();
        suggestions.deleteAll();
        dispositions.deleteAll();
        reports.deleteAll();
        maintenance.deleteAll();
        notifications.deleteAll();
        riskAlerts.deleteAll();
        events.deleteAll();
        tickets.deleteAll();
        counters.deleteAll();
        users.deleteAll();
        chain.deleteAll();
        ChainState cs = new ChainState();
        cs.setId(1L);
        cs.setLastHash("GENESIS");
        chain.save(cs);
    }

    protected User user(String username, Enums.Role role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("x");
        u.setDisplayName(username + "-显示名");
        u.setRole(role);
        u.setEnabled(true);
        return users.save(u);
    }

    protected Counter counter(String name, Enums.CounterType type, Enums.CounterStatus status,
                              int capacity, int avgServeSeconds) {
        Counter c = new Counter();
        c.setName(name);
        c.setType(type);
        c.setStatus(status);
        c.setCapacity(capacity);
        c.setAvgServeSeconds(avgServeSeconds);
        return counters.save(c);
    }

    /** 构造一张指定权重特征的票据：score 优先级分；waitedSec 已等待秒数 */
    protected QueueTicket ticket(String no, String customer, Enums.TicketStatus status, Counter at,
                                 int score, long waitedSec) {
        QueueTicket t = new QueueTicket();
        t.setTicketNo(no);
        t.setCustomerName(customer);
        t.setCustomerType(Enums.CustomerType.NORMAL);
        t.setSource(Enums.Source.WALK_IN);
        t.setBusinessType(Enums.BusinessType.ACCOUNT_OPEN);
        t.setRiskLevel(Enums.RiskLevel.LOW);
        t.setMaterial(Enums.Material.COMPLETE);
        t.setStatus(status);
        t.setChannel(at != null && at.getType() == Enums.CounterType.SELF_SERVICE
                ? Enums.Channel.SELF_MACHINE : Enums.Channel.COUNTER);
        t.setCounterId(at == null ? null : at.getId());
        t.setPriorityScore(score);
        t.setInheritedWaitSeconds(waitedSec);
        t.setWaitStartedAt(waitedSec >= 0 ? LocalDateTime.now() : null);
        t.setCreatedAt(LocalDateTime.now());
        t.setAssignedAt(status == Enums.TicketStatus.ASSIGNED ? LocalDateTime.now() : null);
        t.setFraudStatus(Enums.FraudStatus.NONE);
        return tickets.save(t);
    }

    protected QueueTicket reload(QueueTicket t) {
        return tickets.findById(t.getId()).orElseThrow();
    }

    protected Counter reload(Counter c) {
        return counters.findById(c.getId()).orElseThrow();
    }
}
