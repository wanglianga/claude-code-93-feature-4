package com.ccb.qb.service;

import com.ccb.qb.entity.Counter;
import com.ccb.qb.entity.QueueTicket;
import com.ccb.qb.model.Enums;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 排队权重与预计叫号时间的统一计算：
 * 权重 = 优先级分 * 10000 + 有效等待秒数；
 * 有效等待 = 继承等待（迁移/恢复时累加）+ 当前等待片段时长。
 * 故障发生时把权重冻结为快照，维修后按快照公平恢复，而不是简单插回队首。
 */
@Component
public class QueueMath {

    /** 优先级分：客户类型基础分 + 紧急/优先窗口加成 */
    public int priorityScore(QueueTicket t) {
        int base = switch (t.getCustomerType()) {
            case NORMAL -> 100;
            case CORPORATE -> 200;
            case VIP -> 300;
            case ELDERLY -> 300;
        };
        if (t.isUrgent()) base += 500;
        if (t.isPriorityWindow()) base += 150;
        return base;
    }

    /** 有效等待秒数（含继承等待） */
    public long effectiveWaitSeconds(QueueTicket t, LocalDateTime now) {
        long waited = t.getInheritedWaitSeconds();
        if (t.getWaitStartedAt() != null) {
            waited += Math.max(0, Duration.between(t.getWaitStartedAt(), now).getSeconds());
        }
        return waited;
    }

    /** 当前权重 */
    public long weight(QueueTicket t, LocalDateTime now) {
        return t.getPriorityScore() * 10_000L + effectiveWaitSeconds(t, now);
    }

    /**
     * 计算 ticket 若以权重 w 进入 counter 队列时的排位（1 起）：
     * 排在其前方的是权重严格更大的在队客户；正在办理的占用第 0 位。
     */
    public int positionOf(Counter counter, List<QueueTicket> waitingAtCounter, long w, LocalDateTime now) {
        long ahead = waitingAtCounter.stream()
                .filter(x -> weight(x, now) > w)
                .count();
        return (int) ahead + 1;
    }

    /** 预计叫号时间 = 当前时间 + 排位 * 台位均件时长（办理中的客户占一个服务单元） */
    public LocalDateTime estimateCallAt(Counter counter, List<QueueTicket> waitingAtCounter,
                                        long w, LocalDateTime now) {
        int position = positionOf(counter, waitingAtCounter, w, now);
        int servingAhead = counter.getCurrentTicketId() != null ? 1 : 0;
        long etaSeconds = (long) (position - 1 + servingAhead) * Math.max(60, counter.getAvgServeSeconds());
        return now.plusSeconds(etaSeconds);
    }

    /** 台位是否具备办理该业务的同类服务能力（同类业务服务能力评估） */
    public boolean supports(Enums.Channel channel, Counter c) {
        if (channel == null) return c.getType() != Enums.CounterType.SELF_SERVICE;
        return switch (channel) {
            case SELF_MACHINE -> c.getType() == Enums.CounterType.SELF_SERVICE;
            case WEALTH_ROOM -> c.getType() == Enums.CounterType.GENERAL;
            case COUNTER, REMOTE_SERVICE -> c.getType() != Enums.CounterType.SELF_SERVICE;
        };
    }
}
