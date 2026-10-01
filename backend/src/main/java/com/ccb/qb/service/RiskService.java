package com.ccb.qb.service;

import com.ccb.qb.entity.QueueTicket;
import com.ccb.qb.entity.RiskAlert;
import com.ccb.qb.entity.User;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.QueueTicketRepository;
import com.ccb.qb.repo.RiskAlertRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 风控信号检测：重复取号 / 跨窗口频繁转台 / 人工插队。
 * 原则：告警只记录、可追溯，不阻断业务办理；
 * 对已完成身份核验的紧急业务，告警标记 urgentVerifiedBypass 并说明不阻断原因。
 */
@Service
public class RiskService {

    private static final List<Enums.TicketStatus> ACTIVE = List.of(
            Enums.TicketStatus.WAITING, Enums.TicketStatus.ASSIGNED, Enums.TicketStatus.SERVING,
            Enums.TicketStatus.PENDING, Enums.TicketStatus.PENDING_ALLOC, Enums.TicketStatus.DEFERRED);

    private final RiskAlertRepository alerts;
    private final QueueTicketRepository tickets;

    public RiskService(RiskAlertRepository alerts, QueueTicketRepository tickets) {
        this.alerts = alerts;
        this.tickets = tickets;
    }

    /** 重复取号检测：同一客户 2 小时内已有在途单据（客户取号/前台代录后调用） */
    public void checkRepeatTicket(QueueTicket t) {
        LocalDateTime since = LocalDateTime.now().minusHours(2);
        List<QueueTicket> others = (t.getCustomerId() != null
                ? tickets.findActiveByCustomer(t.getCustomerId(), ACTIVE, since)
                : tickets.findActiveByCustomerName(t.getCustomerName(), ACTIVE, since))
                .stream().filter(x -> !x.getId().equals(t.getId())).toList();
        if (others.isEmpty()) return;
        String detail = "客户 2 小时内已持有在途号码 "
                + others.stream().map(QueueTicket::getTicketNo).collect(Collectors.joining("、"))
                + "，本次又取号 " + t.getTicketNo() + "，疑似重复取号";
        raise(Enums.RiskAlertType.REPEAT_TICKET, t, null, detail, null);
    }

    /** 频繁转台检测：同一单据 1 小时内转台达到 3 次（含本次） */
    public void checkFrequentTransfer(QueueTicket t, Long dispositionId, User actor) {
        if (t.getTransferCount() < 3) return;
        String detail = "单据 " + t.getTicketNo() + " 已累计转台 " + t.getTransferCount()
                + " 次，跨窗口流转异常频繁，请风控关注是否存在规避排队行为";
        raise(Enums.RiskAlertType.FREQUENT_TRANSFER, t, dispositionId, detail, actor);
    }

    /** 人工插队检测：指定号码叫号时，同窗口存在权重更高的在排单据 */
    public void checkManualJump(QueueTicket called, List<QueueTicket> sameCounterQueue, User actor) {
        sameCounterQueue.stream()
                .filter(x -> !x.getId().equals(called.getId())
                        && x.getQueueWeight() > called.getQueueWeight())
                .findFirst()
                .ifPresent(head -> {
                    String detail = "窗口存在权重更高的在排号码 " + head.getTicketNo()
                            + "（权重 " + head.getQueueWeight() + "），却人工指定叫号 " + called.getTicketNo()
                            + "（权重 " + called.getQueueWeight() + "），疑似人工插队";
                    raise(Enums.RiskAlertType.MANUAL_JUMP, called, null, detail, actor);
                });
    }

    /** 生成告警（统一入口）：紧急且已核验身份 → 标记不阻断 */
    public RiskAlert raise(Enums.RiskAlertType type, QueueTicket t, Long dispositionId,
                           String detail, User actor) {
        RiskAlert a = new RiskAlert();
        a.setAlertNo(nextAlertNo());
        a.setAlertType(type);
        a.setTicketId(t.getId());
        a.setTicketNo(t.getTicketNo());
        a.setCustomerId(t.getCustomerId());
        a.setCustomerName(t.getCustomerName());
        a.setDispositionId(dispositionId);
        a.setDetail(detail);
        a.setTraceId(UUID.randomUUID().toString());
        boolean bypass = t.isUrgent() && t.isIdentityVerified();
        a.setUrgentVerifiedBypass(bypass);
        a.setBypassNote(bypass
                ? "紧急业务且已完成身份核验，告警仅记录追溯，不阻断办理"
                : "告警不阻断业务流程；未核验身份的紧急业务请先完成身份核验");
        a.setStatus(Enums.RiskAlertStatus.OPEN);
        a.setCreatedAt(LocalDateTime.now());
        return alerts.save(a);
    }

    private String nextAlertNo() {
        return "RA" + DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDateTime.now())
                + "-" + String.format("%03d", alerts.count() + 1);
    }
}
