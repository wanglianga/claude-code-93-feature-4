package com.ccb.qb.service;

import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 排队风控：同一客户重复取号 / 跨窗口频繁转台 / 人工插队。
 * 告警全部可追溯（关联事件/动作 id），且不得阻断已完成身份核验的紧急业务。
 */
@Service
public class RiskService {

    private final RiskAlertRepository alerts;
    private final QueueTicketRepository tickets;
    private final ChainStateRepository chain;
    private final EventLog eventLog;

    @Value("${app.risk.repeat-window-minutes:60}")
    private int repeatWindowMinutes;

    @Value("${app.risk.repeat-threshold:3}")
    private int repeatThreshold;

    @Value("${app.risk.transfer-threshold:2}")
    private int transferThreshold;

    public RiskService(RiskAlertRepository alerts, QueueTicketRepository tickets,
                       ChainStateRepository chain, EventLog eventLog) {
        this.alerts = alerts;
        this.tickets = tickets;
        this.chain = chain;
        this.eventLog = eventLog;
    }

    /** 身份已核验的紧急业务：告警不阻断 */
    public boolean nonBlocking(QueueTicket t) {
        return t.isUrgent() && t.isIdentityVerified();
    }

    /**
     * 重复取号检测（取号时调用，含本次）。
     * 未核验客户达到阈值：告警并挂起待风控确认（阻断）；
     * 已核验紧急业务：仅告警不阻断。
     */
    @Transactional
    public RiskAlert checkRepeatTicket(QueueTicket t, User actor) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(repeatWindowMinutes);
        List<QueueTicket> recent = t.getCustomerId() != null
                ? tickets.findActiveByCustomerSince(t.getCustomerId(), since)
                : tickets.findActiveByCustomerNameSince(t.getCustomerName(), since);
        if (recent.size() < repeatThreshold) return null;

        boolean blocking = !nonBlocking(t);
        String traceRef = "ticket:" + recent.stream().map(x -> String.valueOf(x.getId()))
                .reduce((a, b) -> a + "," + b).orElse("");
        RiskAlert alert = newAlert(Enums.RiskAlertType.REPEAT_TICKET, t,
                "同一客户 " + repeatWindowMinutes + " 分钟内取号 " + recent.size()
                        + " 次（" + recent.stream().map(QueueTicket::getTicketNo).reduce((a, b) -> a + "," + b).orElse("")
                        + "），疑似重复取号" + (blocking ? "，单据挂起待风控确认" : "；身份已核验紧急业务，告警不阻断"),
                traceRef, blocking);
        QueueEvent e = eventLog.log(t.getId(), Enums.IssueType.RISK_ALERT,
                "风控告警[" + alert.getAlertNo() + "] 重复取号：" + alert.getDetail(), actor, t.getCounterId());
        alert.setTraceRef(alert.getTraceRef() + ";event:" + e.getId());
        alerts.save(alert);
        if (blocking) {
            t.setStatus(Enums.TicketStatus.PENDING);
            tickets.save(t);
        }
        return alert;
    }

    /** 跨窗口频繁转台检测（迁移成功后调用）。迁移属于故障救济，告警不阻断流程。 */
    @Transactional
    public RiskAlert checkFrequentTransfer(QueueTicket t, Long fromCounterId, Long toCounterId, User actor) {
        if (t.getTransferCount() < transferThreshold) return null;
        RiskAlert alert = newAlert(Enums.RiskAlertType.FREQUENT_TRANSFER, t,
                "客户累计跨窗口转台 " + t.getTransferCount() + " 次（本次：台位#" + fromCounterId
                        + " → 台位#" + toCounterId + "），请关注是否存在引导不当或恶意占位"
                        + (nonBlocking(t) ? "；身份已核验紧急业务，告警不阻断" : ""),
                "ticket:" + t.getId(), false);
        QueueEvent e = eventLog.log(t.getId(), Enums.IssueType.RISK_ALERT,
                "风控告警[" + alert.getAlertNo() + "] 频繁转台：" + alert.getDetail(), actor, toCounterId);
        alert.setTraceRef("event:" + e.getId());
        alerts.save(alert);
        return alert;
    }

    /** 人工插队：经理人工提升排队权重，必产生可追溯告警 */
    @Transactional
    public RiskAlert manualJump(Long ticketId, String reason, User actor) {
        QueueTicket t = tickets.findById(ticketId).orElseThrow(() -> new BizException("排队记录不存在: " + ticketId));
        if (t.getStatus() != Enums.TicketStatus.WAITING && t.getStatus() != Enums.TicketStatus.ASSIGNED) {
            throw new BizException("仅等待中/已分流记录可人工调整队列位置");
        }
        t.setPriorityScore(t.getPriorityScore() + 300);
        tickets.save(t);
        QueueEvent e = eventLog.log(t.getId(), Enums.IssueType.RISK_ALERT,
                "人工插队调整：" + actor.getDisplayName() + " 将 " + t.getTicketNo()
                        + " 优先级分提升 300（原因：" + (reason == null || reason.isBlank() ? "未填写" : reason) + "）",
                actor, t.getCounterId());
        RiskAlert alert = newAlert(Enums.RiskAlertType.MANUAL_JUMP, t,
                "人工插队调整（操作人：" + actor.getDisplayName() + "，原因："
                        + (reason == null || reason.isBlank() ? "未填写" : reason)
                        + "）" + (nonBlocking(t) ? "；身份已核验紧急业务，告警不阻断" : ""),
                "event:" + e.getId(), false);
        alert.setTicketId(t.getId());
        alerts.save(alert);
        return alert;
    }

    /** 风控确认：阻断中的告警确认后放行被挂起的单据 */
    @Transactional
    public RiskAlert acknowledge(Long alertId, User actor) {
        RiskAlert a = alerts.findById(alertId).orElseThrow(() -> new BizException("告警不存在: " + alertId));
        if (a.getStatus() == Enums.RiskAlertStatus.ACKNOWLEDGED) return a;
        a.setStatus(Enums.RiskAlertStatus.ACKNOWLEDGED);
        a.setAcknowledgedAt(LocalDateTime.now());
        a.setAcknowledgedBy(actor == null ? "系统" : actor.getDisplayName());
        alerts.save(a);
        if (a.isBlocking() && a.getTicketId() != null) {
            QueueTicket t = tickets.findById(a.getTicketId()).orElse(null);
            if (t != null && t.getStatus() == Enums.TicketStatus.PENDING) {
                t.setStatus(Enums.TicketStatus.WAITING);
                t.setWaitStartedAt(LocalDateTime.now());
                tickets.save(t);
                eventLog.log(t.getId(), Enums.IssueType.NONE,
                        "风控确认告警[" + a.getAlertNo() + "]，单据解除挂起重新进入队列", actor, t.getCounterId());
            }
        }
        return a;
    }

    public List<RiskAlert> list(Enums.RiskAlertStatus status) {
        return status == null ? alerts.findAllByOrderByIdDesc() : alerts.findByStatusOrderByIdDesc(status);
    }

    private RiskAlert newAlert(Enums.RiskAlertType type, QueueTicket t, String detail,
                               String traceRef, boolean blocking) {
        ChainState cs = chain.findForUpdate().orElseGet(() -> {
            ChainState c = new ChainState();
            c.setId(1L);
            c.setLastHash("GENESIS");
            return c;
        });
        cs.setAlertSeq(cs.getAlertSeq() + 1);
        chain.save(cs);
        RiskAlert a = new RiskAlert();
        a.setAlertNo("FK" + String.format("%06d", cs.getAlertSeq()));
        a.setAlertType(type);
        a.setCustomerId(t.getCustomerId());
        a.setCustomerName(t.getCustomerName());
        a.setBizOrderNo(t.getTicketNo());
        a.setTicketId(t.getId());
        a.setDetail(detail);
        a.setTraceRef(traceRef);
        a.setBlocking(blocking);
        a.setStatus(Enums.RiskAlertStatus.OPEN);
        a.setCreatedAt(LocalDateTime.now());
        return alerts.save(a);
    }
}
