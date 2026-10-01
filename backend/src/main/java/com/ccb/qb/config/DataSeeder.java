package com.ccb.qb.config;

import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 演示数据：六类角色账号、窗口/自助机、现金库存、养老金日、若干排队记录 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder implements CommandLineRunner {

    private final UserRepository users;
    private final CounterRepository counters;
    private final CashInventoryRepository cash;
    private final BranchAlertRepository alerts;
    private final QueueTicketRepository tickets;
    private final ChainStateRepository chain;
    private final PasswordEncoder encoder;

    public DataSeeder(UserRepository users, CounterRepository counters, CashInventoryRepository cash,
                      BranchAlertRepository alerts, QueueTicketRepository tickets,
                      ChainStateRepository chain, PasswordEncoder encoder) {
        this.users = users;
        this.counters = counters;
        this.cash = cash;
        this.alerts = alerts;
        this.tickets = tickets;
        this.chain = chain;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        if (users.count() == 0) {
            seedBase();
        }
        ensureFeature4();
    }

    /** 特性4：设备维修/风控/主管账号、台位容量、处置单哈希链状态 —— 幂等补齐 */
    private void ensureFeature4() {
        String pwd = encoder.encode("ccb@123456");
        ensureUser("repair01", "维修工程师·赵工", Enums.Role.MAINTENANCE, "自助设备/窗口终端运维", null, pwd);
        ensureUser("risk01", "风控专员·钱坤", Enums.Role.RISK, "排队风控/异常行为监测", null, pwd);
        ensureUser("supervisor", "网点主管·孙立", Enums.Role.SUPERVISOR, "处置复核/恢复建议审批", null, pwd);

        for (Counter c : counters.findAll()) {
            if (c.getCapacity() <= 0) {
                c.setCapacity(switch (c.getType()) {
                    case GENERAL -> 8;
                    case CASH -> 6;
                    case PRIORITY -> 5;
                    case SELF_SERVICE -> 4;
                });
                counters.save(c);
            }
        }
        if (chain.findById(1L).isEmpty()) {
            ChainState cs = new ChainState();
            cs.setId(1L);
            cs.setLastHash("GENESIS");
            chain.save(cs);
        }
    }

    private void ensureUser(String username, String name, Enums.Role role, String skill, Long counterId, String pwd) {
        if (users.findByUsername(username).isEmpty()) {
            users.save(user(username, name, role, skill, counterId, pwd));
        }
    }

    private void seedBase() {
        String pwd = encoder.encode("ccb@123456");
        User customer = user("customer01", "客户·陈晨", Enums.Role.CUSTOMER, "手机银行注册客户", null, pwd);
        User manager = user("manager", "大堂经理·林芳", Enums.Role.MANAGER, "分流调度/材料预审/投诉处理", null, pwd);
        User t1 = user("teller01", "柜员·周敏", Enums.Role.TELLER, "开户/转账/社保卡", 1L, pwd);
        User t2 = user("teller02", "柜员·吴涛", Enums.Role.TELLER, "现金/挂失/大额", 2L, pwd);
        User t3 = user("teller03", "柜员·郑洁（爱心窗口）", Enums.Role.TELLER, "老人服务/社保卡/手语协助", 3L, pwd);
        User advisor = user("advisor01", "理财经理·孙睿", Enums.Role.ADVISOR, "理财咨询/风险评估/双录", 5L, pwd);
        User security = user("security01", "安保·马强", Enums.Role.SECURITY, "反诈核验/无障碍通道/维序", null, pwd);
        User service = user("service01", "客服·何丽", Enums.Role.SERVICE, "投诉受理/回访/远程客服", null, pwd);
        users.saveAll(List.of(customer, manager, t1, t2, t3, advisor, security, service));

        Counter c1 = counter("1号综合柜台", Enums.CounterType.GENERAL, t1.getId(), 420);
        Counter c2 = counter("2号现金柜台", Enums.CounterType.CASH, t2.getId(), 520);
        Counter c3 = counter("爱心优先窗口", Enums.CounterType.PRIORITY, t3.getId(), 360);
        Counter c4 = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, null, 180);
        Counter c5 = counter("理财室A", Enums.CounterType.GENERAL, advisor.getId(), 900);
        counters.saveAll(List.of(c1, c2, c3, c4, c5));

        CashInventory inv = new CashInventory();
        inv.setRecordDate(LocalDate.now());
        inv.setBalance(1_200_000L);
        inv.setThreshold(300_000L);
        cash.save(inv);

        BranchAlert pension = new BranchAlert();
        pension.setTitle("养老金集中发放日");
        pension.setContent("今日养老金集中发放，老年客户增多：已增开爱心窗口、加强现金备付与安保维序");
        pension.setAlertType("PENSION_DAY");
        pension.setBizDate(LocalDate.now());
        pension.setActive(true);
        pension.setCreatedAt(LocalDateTime.now());
        alerts.save(pension);

        LocalDateTime now = LocalDateTime.now();

        QueueTicket q1 = new QueueTicket();
        q1.setTicketNo("S001");
        q1.setCustomerName("王秀兰（78岁）");
        q1.setCustomerType(Enums.CustomerType.ELDERLY);
        q1.setSource(Enums.Source.WALK_IN);
        q1.setBusinessType(Enums.BusinessType.SOCIAL_CARD);
        q1.setRiskLevel(Enums.RiskLevel.LOW);
        q1.setElderly(true);
        q1.setHearingAssist(true);
        q1.setEstimatedMinutes(15);
        q1.setPriorityWindow(true);
        q1.setEscort(true);
        q1.setStatus(Enums.TicketStatus.WAITING);
        q1.setCreatedAt(now.minusMinutes(4));
        q1.setWaitStartedAt(now.minusMinutes(4));
        q1.setPriorityScore(450);
        tickets.save(q1);

        QueueTicket q2 = new QueueTicket();
        q2.setTicketNo("Z001");
        q2.setCustomerName("李建国（69岁）");
        q2.setCustomerType(Enums.CustomerType.ELDERLY);
        q2.setSource(Enums.Source.MOBILE_APP);
        q2.setBusinessType(Enums.BusinessType.TRANSFER);
        q2.setRiskLevel(Enums.RiskLevel.HIGH);
        q2.setElderly(true);
        q2.setEstimatedMinutes(15);
        q2.setAmount(80_000L);
        q2.setFamilyConfirm(true);
        q2.setFamilyContact("儿子 李先生 138****6677");
        q2.setPriorityWindow(true);
        q2.setPreReview(true);
        q2.setStatus(Enums.TicketStatus.WAITING);
        q2.setCreatedAt(now.minusMinutes(2));
        q2.setWaitStartedAt(now.minusMinutes(2));
        q2.setPriorityScore(450);
        tickets.save(q2);

        QueueTicket q3 = new QueueTicket();
        q3.setTicketNo("A001");
        q3.setCustomerName("赵磊（VIP）");
        q3.setCustomerType(Enums.CustomerType.VIP);
        q3.setSource(Enums.Source.WALK_IN);
        q3.setBusinessType(Enums.BusinessType.ACCOUNT_OPEN);
        q3.setRiskLevel(Enums.RiskLevel.MEDIUM);
        q3.setElderly(false);
        q3.setEstimatedMinutes(20);
        q3.setStatus(Enums.TicketStatus.WAITING);
        q3.setCreatedAt(now.minusMinutes(1));
        q3.setWaitStartedAt(now.minusMinutes(1));
        q3.setPriorityScore(300);
        tickets.save(q3);

        QueueTicket q4 = new QueueTicket();
        q4.setTicketNo("L001");
        q4.setCustomerName("钱晓梅");
        q4.setCustomerType(Enums.CustomerType.NORMAL);
        q4.setSource(Enums.Source.MOBILE_APP);
        q4.setBusinessType(Enums.BusinessType.WEALTH_CONSULT);
        q4.setRiskLevel(Enums.RiskLevel.MEDIUM);
        q4.setElderly(false);
        q4.setEstimatedMinutes(30);
        q4.setStatus(Enums.TicketStatus.ASSIGNED);
        q4.setChannel(Enums.Channel.WEALTH_ROOM);
        q4.setCounterId(5L);
        q4.setAssigneeId(advisor.getId());
        q4.setCreatedAt(now.minusMinutes(10));
        q4.setAssignedAt(now.minusMinutes(8));
        q4.setWaitStartedAt(now.minusMinutes(8));
        q4.setPriorityScore(100);
        tickets.save(q4);
    }

    private User user(String username, String name, Enums.Role role, String skill, Long counterId, String pwd) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(pwd);
        u.setDisplayName(name);
        u.setRole(role);
        u.setSkill(skill);
        u.setCounterId(counterId);
        u.setEnabled(true);
        return u;
    }

    private Counter counter(String name, Enums.CounterType type, Long tellerId, int avgSeconds) {
        Counter c = new Counter();
        c.setName(name);
        c.setType(type);
        c.setStatus(Enums.CounterStatus.OPEN);
        c.setTellerId(tellerId);
        c.setAvgServeSeconds(avgSeconds);
        return c;
    }
}
