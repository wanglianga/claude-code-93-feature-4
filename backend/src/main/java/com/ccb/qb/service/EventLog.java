package com.ccb.qb.service;

import com.ccb.qb.entity.QueueEvent;
import com.ccb.qb.entity.User;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.QueueEventRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 队列变更时间线：所有角色对同一排队记录的操作都沉淀为追加式事件 */
@Component
public class EventLog {

    private final QueueEventRepository events;

    public EventLog(QueueEventRepository events) {
        this.events = events;
    }

    public QueueEvent log(Long ticketId, Enums.IssueType type, String content, User actor, Long counterId) {
        QueueEvent e = new QueueEvent();
        e.setTicketId(ticketId);
        e.setType(type == null ? Enums.IssueType.NONE : type);
        e.setContent(content);
        e.setActorName(actor == null ? "系统" : actor.getDisplayName());
        e.setActorRole(actor == null ? null : actor.getRole());
        e.setCounterId(counterId);
        e.setResolved(type == Enums.IssueType.NONE);
        e.setCreatedAt(LocalDateTime.now());
        return events.save(e);
    }
}
