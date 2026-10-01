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
public class DataSeeder implements CommandLineRunner {

    private final UserRepository users;
    private final CounterRepository counters;
    private final CashInventoryRepository cash;
    private final BranchAlertRepository alerts;
    private final QueueTicketRepository tickets;
    private final PasswordEncoder encoder;

    public DataSeeder(UserRepository users, CounterRepository counters, CashInventoryRepository cash,
                      BranchAlertRepository alerts, QueueTicketRepository tickets, PasswordEncoder encoder) {
        this.users = users;
        this.counters = counters;
        this.cash = cash;
        this.alerts = alerts;
        this.tickets = tickets;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        if (users.count() > 0) return;

        String pwd = encoder.encode("ccb@123456");
        User customer = user("customer01", "客户·陈晨", Enums.Role.CUSTOMER, "手机银行注册客户", null, pwd);
        User manager = user("manager", "大堂经理·林芳", Enums.Role.MANAGER, "分流调度/材料预审/投诉处理", null, pwd);
        User t1 = user("teller01", "柜员·周敏", Enums.Role.TELLER, "开户/转账/社保卡", 1L, pwd);
        User t2 = user("teller02", "柜员·吴涛", Enums.Role.TELLER, "现金/挂失/大额", 2L, pwd);
        User t3 = user("teller03", "柜员·郑洁（爱心窗口）", Enums.Role.TELLER, "老人服务/社保卡/手语协助", 3L, pwd);
        User advisor = user("advisor01", "理财经理·孙睿", Enums.Role.ADVISOR, "理财咨询/风险评估/双录", 5L, pwd);
        User security = user("security01", "安保·马强", Enums.Role.SECURITY, "反诈核验/无障碍通道/维序", null, pwd);
        User service = user("service01", "客服·何丽", Enums.Role.SERVICE, "投诉受理/回访/远程客服", null, pwd);
        // 台位异常处置四角色：前台 / 设备维修 / 风控（柜台沿用柜员账号）
        User frontdesk = user("frontdesk01", "前台·王芸", Enums.Role.FRONT_DESK, "取号引导/异常处置/客户通知", null, pwd);
        User repair = user("repair01", "设备维修·赵工", Enums.Role.REPAIR, "自助设备/叫号终端运维", null, pwd);
        User risk = user("risk01", "风控·刘洋", Enums.Role.RISK, "重复取号/频繁转台/插队告警", null, pwd);
        users.saveAll(List.of(customer, manager, t1, t2, t3, advisor, security, service,
                frontdesk, repair, risk));

        Counter c1 = counter("1号综合柜台", Enums.CounterType.GENERAL, t1.getId(), 420);
        c1.setCapacity(3);
        c1.setSupportedBusiness("ACCOUNT_OPEN,TRANSFER,SOCIAL_CARD,LOST_REPORT");
        Counter c2 = counter("2号现金柜台", Enums.CounterType.CASH, t2.getId(), 520);
        c2.setCapacity(3);
        c2.setSupportedBusiness("TRANSFER,LARGE_CASH,LOST_REPORT,ACCOUNT_OPEN");
        Counter c3 = counter("爱心优先窗口", Enums.CounterType.PRIORITY, t3.getId(), 360);
        c3.setCapacity(2);
        c3.setSupportedBusiness("ACCOUNT_OPEN,TRANSFER,SOCIAL_CARD,LOST_REPORT,LARGE_CASH");
        Counter c4 = counter("智能柜员机01", Enums.CounterType.SELF_SERVICE, null, 180);
        c4.setCapacity(2);
        c4.setSupportedBusiness("ACCOUNT_OPEN,TRANSFER,SOCIAL_CARD");
        Counter c5 = counter("理财室A", Enums.CounterType.GENERAL, advisor.getId(), 900);
        c5.setCapacity(2);
        c5.setSupportedBusiness("WEALTH_CONSULT");
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
        tickets.save(q4);

        // 台位异常处置演示：两张已分配智能柜员机01（已生成叫号预估）的在途单
        QueueTicket q5 = new QueueTicket();
        q5.setTicketNo("A002");
        q5.setCustomerName("陈晓（普通客户）");
        q5.setCustomerType(Enums.CustomerType.NORMAL);
        q5.setSource(Enums.Source.WALK_IN);
        q5.setBusinessType(Enums.BusinessType.ACCOUNT_OPEN);
        q5.setRiskLevel(Enums.RiskLevel.LOW);
        q5.setEstimatedMinutes(20);
        q5.setStatus(Enums.TicketStatus.ASSIGNED);
        q5.setChannel(Enums.Channel.SELF_MACHINE);
        q5.setCounterId(4L);
        q5.setCreatedAt(now.minusMinutes(6));
        q5.setAssignedAt(now.minusMinutes(5));
        tickets.save(q5);

        QueueTicket q6 = new QueueTicket();
        q6.setTicketNo("Z002");
        q6.setCustomerName("孙强（转账2万）");
        q6.setCustomerType(Enums.CustomerType.NORMAL);
        q6.setSource(Enums.Source.MOBILE_APP);
        q6.setBusinessType(Enums.BusinessType.TRANSFER);
        q6.setRiskLevel(Enums.RiskLevel.MEDIUM);
        q6.setAmount(20_000L);
        q6.setEstimatedMinutes(10);
        q6.setStatus(Enums.TicketStatus.ASSIGNED);
        q6.setChannel(Enums.Channel.SELF_MACHINE);
        q6.setCounterId(4L);
        q6.setCreatedAt(now.minusMinutes(3));
        q6.setAssignedAt(now.minusMinutes(2));
        tickets.save(q6);

        // 初始化排队权重/等待计时/叫号预估字段
        for (QueueTicket t : tickets.findAll()) {
            t.setWaitStartedAt(t.getAssignedAt() != null ? t.getAssignedAt() : t.getCreatedAt());
            t.setAccumulatedWaitSeconds(0);
            int p = 1;
            if (t.getCustomerType() == Enums.CustomerType.VIP
                    || t.getCustomerType() == Enums.CustomerType.CORPORATE) p += 1;
            if (t.isElderly() || t.isPriorityWindow()) p += 1;
            t.setPriority(p);
            long waitMin = Math.max(0, java.time.Duration.between(t.getWaitStartedAt(), now).getSeconds()) / 60;
            t.setQueueWeight(Math.round((p * 100.0 + waitMin * 2.0) * 100) / 100.0);
            tickets.save(t);
        }
        for (Counter c : counters.findAll()) {
            var q = tickets.findByCounterIdAndStatusIn(c.getId(), List.of(Enums.TicketStatus.ASSIGNED));
            int avg = Math.max(60, c.getAvgServeSeconds());
            for (int i = 0; i < q.size(); i++) {
                q.get(i).setEstimatedCallAt(now.plusSeconds((long) (i + 1) * avg));
                tickets.save(q.get(i));
            }
        }
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
