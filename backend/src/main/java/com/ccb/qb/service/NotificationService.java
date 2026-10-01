package com.ccb.qb.service;

import com.ccb.qb.entity.Notification;
import com.ccb.qb.entity.QueueTicket;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.NotificationRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 客户通知（演示环境落库模拟短信/App 推送） */
@Service
public class NotificationService {

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    public Notification notify(QueueTicket t, Enums.NotifyChannel channel, String content) {
        Notification n = new Notification();
        n.setBizOrderNo(t.getTicketNo());
        n.setTicketId(t.getId());
        n.setCustomerId(t.getCustomerId());
        n.setCustomerName(t.getCustomerName());
        n.setChannel(channel);
        n.setContent(content);
        n.setStatus("SENT");
        n.setCreatedAt(LocalDateTime.now());
        return notifications.save(n);
    }
}
