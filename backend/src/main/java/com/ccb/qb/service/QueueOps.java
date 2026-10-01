package com.ccb.qb.service;

import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 排队权重 / 等待继承 / 预计叫号时间 的共享计算与记录组件。
 * QueueService 与 DispositionService 共用，保证同一套口径。
 */
@Service
public class QueueOps {

    private final QueueTicketRepository tickets;
    private final CounterRepository counters;
    private final QueueTransitionRepository transitions;
    private final CustomerNotificationRepository notifications;

    public QueueOps(QueueTicketRepository tickets, CounterRepository counters,
                    QueueTransitionRepository transitions, CustomerNotificationRepository notifications) {
        this.tickets = tickets;
        this.counters = counters;
        this.transitions = transitions;
        this.notifications = notifications;
    }

    /** 有效等待秒数 = 历史冻结段 + 当前等待段（冻结中的单据只计历史段） */
    public long effectiveWaitSeconds(QueueTicket t) {
        long acc = t.getAccumulatedWaitSeconds();
        if (t.getWaitStartedAt() != null) {
            acc += Math.max(0, Duration.between(t.getWaitStartedAt(), LocalDateTime.now()).getSeconds());
        }
        return acc;
    }

    /** 排队权重 = 优先级*100 + 等待分钟*2（等待老化），保留 2 位小数 */
    public double computeWeight(QueueTicket t) {
        long waitMin = effectiveWaitSeconds(t) / 60;
        return Math.round((t.getPriority() * 100.0 + waitMin * 2.0) * 100) / 100.0;
    }

    /** 冻结当前等待段：累积等待落账，等待计时停止（设备异常/暂缓时调用） */
    public void freezeWait(QueueTicket t) {
        if (t.getWaitStartedAt() != null) {
            t.setAccumulatedWaitSeconds(t.getAccumulatedWaitSeconds()
                    + Math.max(0, Duration.between(t.getWaitStartedAt(), LocalDateTime.now()).getSeconds()));
            t.setWaitStartedAt(null);
        }
    }

    /** 恢复等待计时（迁移/恢复/暂缓恢复时调用，历史累积自动继承） */
    public void resumeWait(QueueTicket t) {
        if (t.getWaitStartedAt() == null) {
            t.setWaitStartedAt(LocalDateTime.now());
        }
    }

    /** 台位是否支持某业务类型（supportedBusiness 为空表示全部可办） */
    public boolean supports(Counter c, Enums.BusinessType type) {
        String s = c.getSupportedBusiness();
        if (s == null || s.isBlank()) return true;
        for (String x : s.split(",")) {
            if (x.trim().equalsIgnoreCase(type.name())) return true;
        }
        return false;
    }

    /** 台位当前占用（已分配 + 办理中） */
    public long counterLoad(Long counterId) {
        return tickets.countByCounterIdAndStatusIn(counterId,
                List.of(Enums.TicketStatus.ASSIGNED, Enums.TicketStatus.SERVING));
    }

    /** 台位在途队列（按权重降序、分配时间升序） */
    public List<QueueTicket> counterQueue(Long counterId) {
        return tickets.findByCounterIdAndStatusIn(counterId, List.of(Enums.TicketStatus.ASSIGNED))
                .stream()
                .sorted(Comparator.comparingDouble(QueueTicket::getQueueWeight).reversed()
                        .thenComparing(x -> x.getAssignedAt() == null ? x.getCreatedAt() : x.getAssignedAt()))
                .toList();
    }

    /** 单据在台位队列中的位置（1 起；不在队列返回 0） */
    public int positionOf(QueueTicket t) {
        if (t.getCounterId() == null) return 0;
        if (t.getStatus() == Enums.TicketStatus.SERVING) return 0;
        List<QueueTicket> q = counterQueue(t.getCounterId());
        for (int i = 0; i < q.size(); i++) {
            if (q.get(i).getId().equals(t.getId())) return i + 1;
        }
        return 0;
    }

    /**
     * 重算台位队列的权重与预计叫号时间（取号/分流/迁移/恢复/办结/定时老化后调用）。
     * 预计叫号时间 = 当前时间 + 队列位次 * 台位均件时长。
     */
    public void recomputeCounterQueue(Long counterId) {
        if (counterId == null) return;
        Counter c = counters.findById(counterId).orElse(null);
        if (c == null) return;
        List<QueueTicket> q = tickets.findByCounterIdAndStatusIn(counterId,
                List.of(Enums.TicketStatus.ASSIGNED));
        for (QueueTicket t : q) {
            t.setQueueWeight(computeWeight(t));
        }
        q.sort(Comparator.comparingDouble(QueueTicket::getQueueWeight).reversed()
                .thenComparing(x -> x.getAssignedAt() == null ? x.getCreatedAt() : x.getAssignedAt()));
        int avg = Math.max(60, c.getAvgServeSeconds());
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < q.size(); i++) {
            QueueTicket t = q.get(i);
            LocalDateTime eta = now.plusSeconds((long) (i + 1) * avg);
            if (t.getEstimatedCallAt() == null || !t.getEstimatedCallAt().equals(eta)) {
                t.setEstimatedCallAt(eta);
            }
            tickets.save(t);
        }
    }

    /** 记录一条队列变更时间线 */
    public QueueTransition transition(QueueTicket t, Enums.QueueChangeType type,
                                      Long fromCounter, Long toCounter,
                                      String fromStatus, String toStatus,
                                      Double weightBefore, LocalDateTime etaBefore,
                                      String reason, String actorName) {
        QueueTransition tr = new QueueTransition();
        tr.setTicketId(t.getId());
        tr.setTicketNo(t.getTicketNo());
        tr.setChangeType(type);
        tr.setFromCounterId(fromCounter);
        tr.setToCounterId(toCounter);
        tr.setFromStatus(fromStatus);
        tr.setToStatus(toStatus);
        tr.setWeightBefore(weightBefore);
        tr.setWeightAfter(t.getQueueWeight());
        tr.setWaitedSeconds(effectiveWaitSeconds(t));
        tr.setEtaBefore(etaBefore);
        tr.setEtaAfter(t.getEstimatedCallAt());
        tr.setReason(reason);
        tr.setActorName(actorName);
        tr.setCreatedAt(LocalDateTime.now());
        return transitions.save(tr);
    }

    /** 客户通知（演示环境落库模拟 App 推送/短信） */
    public CustomerNotification notify(QueueTicket t, String channel, String content) {
        CustomerNotification n = new CustomerNotification();
        n.setTicketId(t.getId());
        n.setTicketNo(t.getTicketNo());
        n.setCustomerId(t.getCustomerId());
        n.setChannel(channel);
        n.setContent(content);
        n.setCreatedAt(LocalDateTime.now());
        return notifications.save(n);
    }

    /** 默认优先级：紧急+2，VIP/对公+2，老人/优先窗口+2，普通+1 */
    public int defaultPriority(QueueTicket t) {
        int p = 1;
        if (t.getCustomerType() == Enums.CustomerType.VIP
                || t.getCustomerType() == Enums.CustomerType.CORPORATE) p += 1;
        if (t.isElderly() || t.isPriorityWindow()) p += 1;
        if (t.isUrgent()) p += 2;
        return p;
    }
}
