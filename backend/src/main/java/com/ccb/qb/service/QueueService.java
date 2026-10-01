package com.ccb.qb.service;

import com.ccb.qb.dto.Dtos;
import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.*;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class QueueService {

    private final QueueTicketRepository tickets;
    private final CounterRepository counters;
    private final QueueEventRepository events;
    private final CashInventoryRepository cash;
    private final BranchAlertRepository alerts;
    private final UserRepository users;
    private final QueueOps ops;
    private final RiskService riskService;

    @Value("${app.queue.long-wait-seconds}")
    private long longWaitSeconds;

    @Value("${app.queue.large-amount-threshold}")
    private long largeAmountThreshold;

    private static final Map<Enums.BusinessType, String> PREFIX = Map.of(
            Enums.BusinessType.ACCOUNT_OPEN, "A",
            Enums.BusinessType.TRANSFER, "Z",
            Enums.BusinessType.LOST_REPORT, "G",
            Enums.BusinessType.SOCIAL_CARD, "S",
            Enums.BusinessType.WEALTH_CONSULT, "L",
            Enums.BusinessType.LARGE_CASH, "X"
    );

    public QueueService(QueueTicketRepository tickets, CounterRepository counters,
                        QueueEventRepository events, CashInventoryRepository cash,
                        BranchAlertRepository alerts, UserRepository users,
                        QueueOps ops, RiskService riskService) {
        this.tickets = tickets;
        this.counters = counters;
        this.events = events;
        this.cash = cash;
        this.alerts = alerts;
        this.users = users;
        this.ops = ops;
        this.riskService = riskService;
    }

    /* ---------------- 取号 / 预约 ---------------- */

    @Transactional
    public QueueTicket createTicket(Dtos.TicketRequest req, User actor) {
        QueueTicket t = new QueueTicket();
        t.setCustomerId(req.customerId());
        t.setCustomerName(req.customerName() == null || req.customerName().isBlank()
                ? "现场客户" : req.customerName());
        t.setCustomerType(req.customerType());
        t.setSource(req.source());
        t.setBusinessType(req.businessType());
        t.setRiskLevel(req.riskLevel());
        t.setElderly(req.elderly());
        t.setWheelchair(req.wheelchair());
        t.setHearingAssist(req.hearingAssist());
        t.setEstimatedMinutes(req.estimatedMinutes() == null ? defaultMinutes(req.businessType())
                : req.estimatedMinutes());
        t.setAppointmentTime(req.appointmentTime());
        t.setAmount(req.amount());
        t.setMaterial(Enums.Material.COMPLETE);
        t.setStatus(Enums.TicketStatus.WAITING);
        t.setCreatedAt(LocalDateTime.now());
        // 适老服务安排（预约时可直接指定）
        t.setEscort(req.escort());
        t.setPriorityWindow(req.priorityWindow() || req.elderly());
        t.setPreReview(req.preReview());
        t.setFamilyConfirm(req.familyConfirm());
        t.setFamilyContact(req.familyContact());
        t.setFraudStatus(Enums.FraudStatus.NONE);
        t.setTicketNo(nextTicketNo(req.businessType()));
        // 排队权重 / 等待计时 / 紧急标记
        t.setUrgent(req.urgent());
        t.setIdentityVerified(req.identityVerified());
        t.setWaitStartedAt(t.getCreatedAt());
        t.setAccumulatedWaitSeconds(0);
        t.setPriority(ops.defaultPriority(t));
        t.setQueueWeight(ops.computeWeight(t));
        tickets.save(t);
        ops.transition(t, Enums.QueueChangeType.ENQUEUE, null, null,
                null, Enums.TicketStatus.WAITING.name(), null, null,
                "取号/预约进入待分流队列", actor == null ? "客户" : actor.getDisplayName());
        // 风控：重复取号检测（告警不阻断取号）
        riskService.checkRepeatTicket(t);

        // 轮椅需求：自动生成无障碍引导安排事件，通知安保
        if (req.wheelchair()) {
            addEvent(t.getId(), Enums.IssueType.NONE,
                    "客户使用轮椅，请安保提前开启无障碍通道并安排门口迎接", actor, null);
        }
        // 听力引导：大堂经理准备手写板/助听设备
        if (req.hearingAssist()) {
            addEvent(t.getId(), Enums.IssueType.NONE,
                    "客户有听力引导需求，请准备助听设备与大字版指引", actor, null);
        }
        // 家属远程确认的适老预约：提前生成预审任务
        if (req.familyConfirm()) {
            addEvent(t.getId(), Enums.IssueType.NONE,
                    "适老预约：需家属远程确认（联系方式："
                            + (req.familyContact() == null ? "未登记" : req.familyContact()) + "）", actor, null);
        }
        return t;
    }

    private int defaultMinutes(Enums.BusinessType type) {
        return switch (type) {
            case ACCOUNT_OPEN -> 20;
            case TRANSFER -> 10;
            case LOST_REPORT -> 15;
            case SOCIAL_CARD -> 15;
            case WEALTH_CONSULT -> 30;
            case LARGE_CASH -> 25;
        };
    }

    private String nextTicketNo(Enums.BusinessType type) {
        long seq = tickets.count() + 1;
        return PREFIX.get(type) + String.format("%03d", seq);
    }

    /* ---------------- 大堂经理：材料预审 + 分流 ---------------- */

    /** 渠道推荐：根据业务类型/客户特征/材料，给出建议渠道，避免全挤柜台 */
    public Map<String, Object> recommend(QueueTicket t) {
        Map<String, Object> m = new LinkedHashMap<>();
        Enums.Channel ch;
        String reason;
        if (t.getMaterial() == Enums.Material.MISSING) {
            ch = Enums.Channel.REMOTE_SERVICE;
            reason = "关键材料缺失，建议先到咨询台预审或远程客服线上补齐，避免柜台空等";
        } else if (t.getBusinessType() == Enums.BusinessType.WEALTH_CONSULT) {
            ch = Enums.Channel.WEALTH_ROOM;
            reason = "理财咨询由理财经理在理财室一对一办理，并需双录风险揭示";
        } else if (t.getBusinessType() == Enums.BusinessType.LARGE_CASH
                || t.getBusinessType() == Enums.BusinessType.LOST_REPORT) {
            ch = Enums.Channel.COUNTER;
            reason = t.getBusinessType() == Enums.BusinessType.LARGE_CASH
                    ? "大额现金必须在现金柜台办理，并校验库存与反诈"
                    : "挂失需本人临柜核验身份";
        } else if (t.getRiskLevel() == Enums.RiskLevel.HIGH || t.isElderly()
                || (t.getAmount() != null && t.getAmount() >= largeAmountThreshold)) {
            ch = Enums.Channel.COUNTER;
            reason = "高风险/老人/大额客户走人工柜台，优先窗口接待";
        } else if (t.getBusinessType() == Enums.BusinessType.ACCOUNT_OPEN
                || t.getBusinessType() == Enums.BusinessType.TRANSFER
                || t.getBusinessType() == Enums.BusinessType.SOCIAL_CARD) {
            ch = Enums.Channel.SELF_MACHINE;
            reason = "标准化业务可在智能柜员机办理，分流柜台压力，大堂经理现场协助";
        } else {
            ch = Enums.Channel.COUNTER;
            reason = "默认综合柜台";
        }
        m.put("channel", ch);
        m.put("reason", reason);
        return m;
    }

    @Transactional
    public QueueTicket assign(Long id, Dtos.AssignRequest req, User actor) {
        QueueTicket t = mustTicket(id);
        if (t.getStatus() == Enums.TicketStatus.DONE || t.getStatus() == Enums.TicketStatus.CANCELLED) {
            throw new BizException("该记录已结束，无法分流");
        }
        t.setChannel(req.channel());
        t.setCounterId(req.counterId());
        t.setAssigneeId(req.assigneeId());
        if (req.material() != null) {
            t.setMaterial(req.material());
            t.setMaterialNote(req.materialNote());
        }
        t.setEscort(req.escort() || t.isEscort());
        t.setPriorityWindow(req.priorityWindow() || t.isPriorityWindow());
        t.setPreReview(req.preReview() || t.isPreReview());
        t.setFamilyConfirm(req.familyConfirm() || t.isFamilyConfirm());
        t.setStatus(Enums.TicketStatus.ASSIGNED);
        t.setAssignedAt(LocalDateTime.now());
        t.setPendingReason(null);
        tickets.save(t);

        // 窗口绑定
        if (req.counterId() != null) {
            Counter c = mustCounter(req.counterId());
            c.setStatus(Enums.CounterStatus.OPEN);
            if (req.assigneeId() != null) c.setTellerId(req.assigneeId());
            counters.save(c);
            // 生成叫号预估：重算该台位队列权重与预计叫号时间
            ops.recomputeCounterQueue(req.counterId());
            ops.transition(t, Enums.QueueChangeType.ASSIGN, null, req.counterId(),
                    Enums.TicketStatus.WAITING.name(), Enums.TicketStatus.ASSIGNED.name(),
                    null, null, "大堂经理分流至台位，生成叫号预估",
                    actor == null ? "系统" : actor.getDisplayName());
        }

        String chName = switch (req.channel()) {
            case COUNTER -> "柜台";
            case SELF_MACHINE -> "自助设备区";
            case WEALTH_ROOM -> "理财室";
            case REMOTE_SERVICE -> "远程客服";
        };
        addEvent(id, Enums.IssueType.NONE,
                "大堂经理分流至「" + chName + "」"
                        + (req.counterId() != null ? "，窗口：" + mustCounter(req.counterId()).getName() : "")
                        + (req.assigneeId() != null ? "，处理人：" + mustUser(req.assigneeId()).getDisplayName() : "")
                        + (req.material() == Enums.Material.INCOMPLETE ? "，材料不完整已提示" :
                           req.material() == Enums.Material.MISSING ? "，材料缺失，先预审" : "")
                        + (t.isPreReview() ? "；已安排材料预审" : "")
                        + (t.isEscort() ? "；安排陪同引导" : ""), actor, req.counterId());

        // 老人缺材料：进入挂起，由大堂经理预审/联系家属，不占用窗口
        if (t.isElderly() && t.getMaterial() != Enums.Material.COMPLETE) {
            t.setStatus(Enums.TicketStatus.PENDING);
            addEvent(id, Enums.IssueType.MISSING_ID,
                    "老人客户材料" + (t.getMaterial() == Enums.Material.MISSING ? "缺失" : "不完整")
                            + "：" + Optional.ofNullable(t.getMaterialNote()).orElse("")
                            + "；启动材料预审与家属远程确认流程", actor, req.counterId());
        }
        return t;
    }

    /* ---------------- 柜员：叫号 / 重新排队 / 办理完成 ---------------- */

    @Transactional
    public QueueTicket callNext(Long counterId, User teller) {
        Counter c = counters.findByIdForUpdate(counterId)
                .orElseThrow(() -> new BizException("窗口不存在: " + counterId));
        if (!Objects.equals(c.getTellerId(), teller.getId())) {
            // 允许本人窗口或大堂经理代叫
            c.setTellerId(teller.getId());
        }
        if (c.getStatus() == Enums.CounterStatus.OFFLINE) {
            throw new BizException("该台位设备离线，维修闭环并经主管复核恢复前不可叫号");
        }
        if (c.getStatus() == Enums.CounterStatus.TEMP_LEAVE || c.getStatus() == Enums.CounterStatus.CLOSED) {
            throw new BizException("窗口处于「临时离岗/关闭」状态，不可叫号");
        }
        if (c.getStatus() == Enums.CounterStatus.FAULT) {
            throw new BizException("该设备故障中，请联系安保/运维后再开放窗口");
        }
        // 优先：已分配给本窗口的（按排队权重降序，权重相同按分配先后）；否则按优先级取一个 ASSIGNED
        QueueTicket t = tickets.findByCounterIdAndStatusIn(counterId,
                        List.of(Enums.TicketStatus.ASSIGNED)).stream()
                .sorted(Comparator.comparingDouble(QueueTicket::getQueueWeight).reversed()
                        .thenComparing(x -> x.getAssignedAt() == null ? x.getCreatedAt() : x.getAssignedAt()))
                .findFirst()
                .orElseGet(() -> tickets.findByStatusOrderByCreatedAtAsc(Enums.TicketStatus.ASSIGNED)
                        .stream()
                        .filter(x -> x.getCounterId() == null)
                        .min(Comparator.comparing((QueueTicket x) -> !x.isPriorityWindow())
                                .thenComparing(QueueTicket::getAssignedAt))
                        .orElse(null));
        if (t == null) throw new BizException("暂无可叫号码");
        return startService(t, c, teller);
    }

    @Transactional
    public QueueTicket callTicket(Long ticketId, User teller) {
        QueueTicket t = mustTicket(ticketId);
        if (t.getStatus() != Enums.TicketStatus.ASSIGNED && t.getStatus() != Enums.TicketStatus.PENDING) {
            throw new BizException("当前状态不可叫号：" + t.getStatus());
        }
        Counter c = t.getCounterId() == null ? null : counters.findById(t.getCounterId()).orElse(null);
        if (c == null) {
            c = counters.findByStatus(Enums.CounterStatus.OPEN).stream()
                    .filter(x -> Objects.equals(x.getTellerId(), teller.getId())).findFirst()
                    .orElseThrow(() -> new BizException("未找到您名下的开放窗口，请联系大堂经理分配"));
        }
        // 风控：人工插队检测——同窗口存在权重更高的在排号码时，指定叫号产生可追溯告警（不阻断）
        if (t.getStatus() == Enums.TicketStatus.ASSIGNED) {
            riskService.checkManualJump(t, ops.counterQueue(c.getId()), teller);
        }
        return startService(t, c, teller);
    }

    private QueueTicket startService(QueueTicket t, Counter c, User teller) {
        if (c.getStatus() != Enums.CounterStatus.OPEN) {
            throw new BizException("窗口当前不可用：" + c.getStatus());
        }
        String fromStatus = t.getStatus().name();
        double wBefore = t.getQueueWeight();
        LocalDateTime etaBefore = t.getEstimatedCallAt();
        c.setCurrentTicketId(t.getId());
        counters.save(c);
        t.setStatus(Enums.TicketStatus.SERVING);
        t.setCounterId(c.getId());
        t.setAssigneeId(teller.getId());
        t.setServingAt(LocalDateTime.now());
        // 实际等待 = 历史冻结段 + 当前等待段（迁移/暂缓继承后的真实排队时长）
        ops.freezeWait(t);
        t.setWaitSeconds(t.getAccumulatedWaitSeconds());
        tickets.save(t);
        ops.transition(t, Enums.QueueChangeType.CALL, c.getId(), c.getId(),
                fromStatus, Enums.TicketStatus.SERVING.name(), wBefore, etaBefore,
                "叫号开始办理", teller.getDisplayName());
        ops.recomputeCounterQueue(c.getId());
        addEvent(t.getId(), Enums.IssueType.NONE,
                teller.getDisplayName() + " 在 " + c.getName() + " 叫号 " + t.getTicketNo()
                        + (t.isPriorityWindow() ? "（爱心优先窗口）" : ""), teller, c.getId());

        // 大额转账：触发反诈核验
        long amt = t.getAmount() == null ? 0 : t.getAmount();
        if (t.getBusinessType() == Enums.BusinessType.TRANSFER && amt >= largeAmountThreshold
                && t.getFraudStatus() != Enums.FraudStatus.PASSED) {
            t.setFraudStatus(Enums.FraudStatus.PENDING);
            t.setStatus(Enums.TicketStatus.PENDING);
            tickets.save(t);
            addEvent(t.getId(), Enums.IssueType.FRAUD_CHECK,
                    "大额转账 " + amt + " 元触发反诈核验：暂停办理，安保/合规进行交易背景核实（受款人、用途、客户认知）",
                    teller, c.getId());
        }
        // 大额现金：校验现金库存
        if (t.getBusinessType() == Enums.BusinessType.LARGE_CASH) {
            CashInventory inv = todayCash();
            if (amt > inv.getBalance()) {
                t.setStatus(Enums.TicketStatus.PENDING);
                tickets.save(t);
                inv.setReplenishing(true);
                cash.save(inv);
                addEvent(t.getId(), Enums.IssueType.NONE,
                        "现金库存不足（需 " + amt + "，库存 " + inv.getBalance()
                                + "）：已发起现金调拨申请，请客户稍候", teller, c.getId());
            } else {
                addEvent(t.getId(), Enums.IssueType.NONE,
                        "现金库存充足（库存 " + inv.getBalance() + "），可正常办理", teller, c.getId());
            }
        }
        return t;
    }

    @Transactional
    public QueueTicket complete(Long id, Dtos.CompleteRequest req, User actor) {
        QueueTicket t = mustTicket(id);
        if (t.getStatus() != Enums.TicketStatus.SERVING && t.getStatus() != Enums.TicketStatus.PENDING) {
            throw new BizException("仅办理中/挂起记录可办结");
        }
        if (t.getFraudStatus() == Enums.FraudStatus.PENDING) {
            throw new BizException("反诈核验尚未完成，不能办结");
        }
        t.setResultType(req.resultType());
        t.setResultNote(req.resultNote());
        t.setRiskNotice(req.riskNotice());
        t.setComplaint(req.complaint());
        t.setComplaintNote(req.complaintNote());
        t.setStatus(Enums.TicketStatus.DONE);
        t.setFinishedAt(LocalDateTime.now());
        tickets.save(t);
        ops.transition(t, Enums.QueueChangeType.DONE, t.getCounterId(), null,
                Enums.TicketStatus.SERVING.name(), Enums.TicketStatus.DONE.name(),
                t.getQueueWeight(), t.getEstimatedCallAt(), "办结：" + resultText(req.resultType()),
                actor == null ? "系统" : actor.getDisplayName());

        if (t.getCounterId() != null) {
            Counter c = mustCounter(t.getCounterId());
            if (Objects.equals(c.getCurrentTicketId(), t.getId())) {
                c.setCurrentTicketId(null);
                c.setServedCount(c.getServedCount() + 1);
                counters.save(c);
            }
            ops.recomputeCounterQueue(t.getCounterId());
        }
        // 大额现金成功：扣减库存
        if (t.getBusinessType() == Enums.BusinessType.LARGE_CASH
                && req.resultType() == Enums.ResultType.SUCCESS && t.getAmount() != null) {
            CashInventory inv = todayCash();
            inv.setBalance(Math.max(0, inv.getBalance() - t.getAmount()));
            cash.save(inv);
        }
        // 反诈拦截：办结为拒绝/升级
        if (t.getFraudStatus() == Enums.FraudStatus.BLOCKED
                && req.resultType() != Enums.ResultType.REJECTED
                && req.resultType() != Enums.ResultType.ESCALATED) {
            // 允许但记录提示
        }
        addEvent(id, Enums.IssueType.NONE,
                "办理结束：" + resultText(req.resultType())
                        + Optional.ofNullable(req.resultNote()).map(n -> "（" + n + "）").orElse("")
                        + (req.complaint() ? "；客户提出投诉：" + Optional.ofNullable(req.complaintNote()).orElse("") : ""),
                actor, t.getCounterId());
        return t;
    }

    @Transactional
    public QueueTicket cancel(Long id, String reason, User actor) {
        QueueTicket t = mustTicket(id);
        if (t.getStatus() == Enums.TicketStatus.DONE) throw new BizException("已办结不可取消");
        t.setStatus(Enums.TicketStatus.CANCELLED);
        t.setFinishedAt(LocalDateTime.now());
        t.setResultNote(reason);
        tickets.save(t);
        ops.transition(t, Enums.QueueChangeType.CANCEL, t.getCounterId(), null,
                null, Enums.TicketStatus.CANCELLED.name(), t.getQueueWeight(), t.getEstimatedCallAt(),
                "取消：" + reason, actor == null ? "系统" : actor.getDisplayName());
        addEvent(id, Enums.IssueType.NONE, "号码取消：" + reason, actor, t.getCounterId());
        return t;
    }

    /* ---------------- 反诈核验（安保/合规） ---------------- */

    @Transactional
    public QueueTicket fraudReview(Long id, Dtos.FraudRequest req, User checker) {
        QueueTicket t = mustTicket(id);
        t.setFraudStatus(req.fraudStatus());
        t.setFraudCheckerId(checker.getId());
        t.setFraudCheckedAt(LocalDateTime.now());
        t.setFraudNote(req.fraudNote());
        tickets.save(t);
        String text = switch (req.fraudStatus()) {
            case PASSED -> "反诈核验通过：" + Optional.ofNullable(req.fraudNote()).orElse("交易背景真实")
                    + "，恢复办理";
            case BLOCKED -> "反诈核验未通过、已拦截：" + Optional.ofNullable(req.fraudNote()).orElse("疑似诈骗特征")
                    + "；对客户进行劝阻，业务按拒绝/升级处理";
            default -> "反诈核验状态更新：" + req.fraudStatus();
        };
        addEvent(id, Enums.IssueType.FRAUD_CHECK, text, checker, t.getCounterId());
        // 自动关闭对应挂起事件
        events.findByTicketIdOrderByCreatedAtAsc(id).stream()
                .filter(e -> e.getType() == Enums.IssueType.FRAUD_CHECK && !e.isResolved())
                .forEach(e -> resolveEventEntity(e, text + "（核验人：" + checker.getDisplayName() + "）", false));
        if (req.fraudStatus() == Enums.FraudStatus.PASSED && t.getStatus() == Enums.TicketStatus.PENDING) {
            t.setStatus(Enums.TicketStatus.SERVING);
            tickets.save(t);
        }
        return t;
    }

    /* ---------------- 协同事件 ---------------- */

    @Transactional
    public QueueEvent addEvent(Long ticketId, Enums.IssueType type, String content, User actor, Long counterId) {
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

    @Transactional
    public QueueEvent reportEvent(Dtos.EventRequest req, User actor) {
        QueueEvent e = addEvent(req.ticketId(), req.type(), req.content(), actor, req.counterId());
        QueueTicket t = mustTicket(req.ticketId());

        switch (req.type()) {
            case MISSING_ID -> {
                t.setMaterial(Enums.Material.MISSING);
                t.setStatus(Enums.TicketStatus.PENDING);
                tickets.save(t);
                if (t.isElderly()) {
                    t.setPreReview(true);
                    tickets.save(t);
                }
            }
            case JUMP_COMPLAINT -> {
                // 投诉插队：客服介入，记录投诉，客户优先维持在原队列
                t.setComplaint(true);
                t.setComplaintNote(req.content());
                tickets.save(t);
            }
            case TELLER_LEAVE -> {
                // 柜员临时离岗：窗口置为临时离岗，办理中的单据挂起等待重新分流
                Long cid = req.counterId() != null ? req.counterId() : t.getCounterId();
                if (cid != null) {
                    Counter c = mustCounter(cid);
                    c.setStatus(Enums.CounterStatus.TEMP_LEAVE);
                    c.setCurrentTicketId(null);
                    counters.save(c);
                }
                if (t.getStatus() == Enums.TicketStatus.SERVING) {
                    t.setStatus(Enums.TicketStatus.PENDING);
                    t.setCounterId(null);
                    tickets.save(t);
                }
            }
            case MACHINE_FAULT -> {
                Long cid = req.counterId() != null ? req.counterId() : t.getCounterId();
                if (cid != null) {
                    Counter c = mustCounter(cid);
                    c.setStatus(Enums.CounterStatus.FAULT);
                    c.setCurrentTicketId(null);
                    counters.save(c);
                }
                // 自助机故障：引导回柜台或远程客服
                if (t.getStatus() != Enums.TicketStatus.DONE) {
                    t.setStatus(Enums.TicketStatus.PENDING);
                    tickets.save(t);
                }
            }
            case LONG_WAIT -> t.setLongWaitNotified(true);
            default -> {}
        }
        tickets.save(t);
        return e;
    }

    @Transactional
    public QueueEvent resolveEvent(Long eventId, Dtos.EventResolveRequest req, User actor) {
        QueueEvent e = events.findById(eventId).orElseThrow(() -> new BizException("事件不存在"));
        resolveEventEntity(e, req.resolution() + "（处理人：" + actor.getDisplayName() + "）",
                req.resumeTicket());
        return e;
    }

    private void resolveEventEntity(QueueEvent e, String resolution, boolean resumeTicket) {
        e.setResolved(true);
        e.setResolution(resolution);
        e.setResolvedAt(LocalDateTime.now());
        events.save(e);
        if (resumeTicket && e.getTicketId() != null) {
            QueueTicket t = tickets.findById(e.getTicketId()).orElse(null);
            if (t != null && t.getStatus() == Enums.TicketStatus.PENDING
                    && t.getFraudStatus() != Enums.FraudStatus.PENDING) {
                // 有窗口回窗口继续，否则回到已分配等叫号
                boolean hasOpenCounter = t.getCounterId() != null
                        && counters.findById(t.getCounterId()).map(c -> c.getStatus() == Enums.CounterStatus.OPEN)
                        .orElse(false);
                t.setStatus(hasOpenCounter ? Enums.TicketStatus.SERVING : Enums.TicketStatus.ASSIGNED);
                tickets.save(t);
            }
        }
    }

    /* ---------------- 窗口 / 现金 / 网点状态 ---------------- */

    @Transactional
    public Counter updateCounterStatus(Long counterId, Dtos.CounterStatusRequest req, User actor) {
        Counter c = mustCounter(counterId);
        Enums.CounterStatus old = c.getStatus();
        c.setStatus(req.status());
        c.setNote(req.note());
        if (req.status() == Enums.CounterStatus.OPEN) {
            // 重新开放
        } else if (req.status() == Enums.CounterStatus.TEMP_LEAVE) {
            // 离岗时挂起当前单据
            if (c.getCurrentTicketId() != null) {
                QueueTicket t = mustTicket(c.getCurrentTicketId());
                t.setStatus(Enums.TicketStatus.PENDING);
                t.setCounterId(null);
                tickets.save(t);
                addEvent(t.getId(), Enums.IssueType.TELLER_LEAVE,
                        "柜员临时离岗，单据挂起待重新分流：" + Optional.ofNullable(req.note()).orElse(""),
                        actor, c.getId());
                c.setCurrentTicketId(null);
            }
        } else if (req.status() == Enums.CounterStatus.FAULT) {
            if (c.getCurrentTicketId() != null) {
                QueueTicket t = mustTicket(c.getCurrentTicketId());
                t.setStatus(Enums.TicketStatus.PENDING);
                tickets.save(t);
                addEvent(t.getId(), Enums.IssueType.MACHINE_FAULT,
                        "自助设备/窗口故障，单据挂起并改引柜台：" + Optional.ofNullable(req.note()).orElse(""),
                        actor, c.getId());
                c.setCurrentTicketId(null);
            }
        }
        counters.save(c);
        return c;
    }

    public CashInventory todayCash() {
        LocalDate today = LocalDate.now();
        return cash.findByRecordDate(today).orElseGet(() -> {
            CashInventory inv = new CashInventory();
            inv.setRecordDate(today);
            inv.setBalance(1_200_000L);
            inv.setThreshold(300_000L);
            inv.setReplenishing(false);
            return cash.save(inv);
        });
    }

    @Transactional
    public CashInventory updateCash(Dtos.CashUpdateRequest req) {
        CashInventory inv = todayCash();
        if (req.balance() != null) inv.setBalance(req.balance());
        if (req.threshold() != null) inv.setThreshold(req.threshold());
        if (req.replenishing() != null) inv.setReplenishing(req.replenishing());
        if (req.note() != null) inv.setNote(req.note());
        return cash.save(inv);
    }

    /** 突发停电：全网点应急 —— 所有在办/已分配单据挂起，恢复后继续 */
    @Transactional
    public BranchAlert toggleOutage(boolean active, String note, User actor) {
        BranchAlert alert;
        var actives = alerts.findByActiveTrueOrderByCreatedAtDesc();
        alert = actives.stream().filter(a -> "OUTAGE".equals(a.getAlertType())).findFirst()
                .orElseGet(() -> {
                    BranchAlert a = new BranchAlert();
                    a.setCreatedAt(LocalDateTime.now());
                    a.setBizDate(LocalDate.now());
                    a.setAlertType("OUTAGE");
                    return a;
                });
        alert.setTitle(active ? "网点突发停电" : "停电解除，恢复营业");
        alert.setContent(note);
        alert.setActive(active);
        alerts.save(alert);

        if (active) {
            for (QueueTicket t : tickets.findAllByOrderByCreatedAtDesc()) {
                if (t.getStatus() == Enums.TicketStatus.SERVING || t.getStatus() == Enums.TicketStatus.ASSIGNED) {
                    t.setStatus(Enums.TicketStatus.PENDING);
                    tickets.save(t);
                    addEvent(t.getId(), Enums.IssueType.OUTAGE,
                            "网点突发停电，系统/设备暂停，单据挂起；启用手工叫号与安保维序", actor, t.getCounterId());
                }
            }
            for (Counter c : counters.findAll()) {
                if (c.getStatus() == Enums.CounterStatus.OPEN && c.getCurrentTicketId() != null) {
                    c.setCurrentTicketId(null);
                    counters.save(c);
                }
            }
        } else {
            for (QueueTicket t : tickets.findAllByOrderByCreatedAtDesc()) {
                boolean isOutagePending = events.findByTicketIdOrderByCreatedAtAsc(t.getId()).stream()
                        .anyMatch(e -> e.getType() == Enums.IssueType.OUTAGE && !e.isResolved());
                if (isOutagePending && t.getStatus() == Enums.TicketStatus.PENDING) {
                    t.setStatus(Enums.TicketStatus.ASSIGNED);
                    tickets.save(t);
                    addEvent(t.getId(), Enums.IssueType.NONE, "供电恢复，单据重新进入叫号队列", actor, null);
                    events.findByTicketIdOrderByCreatedAtAsc(t.getId()).stream()
                            .filter(e -> e.getType() == Enums.IssueType.OUTAGE && !e.isResolved())
                            .forEach(e -> resolveEventEntity(e, "供电恢复，系统重启完成", false));
                }
            }
        }
        return alert;
    }

    @Transactional
    public BranchAlert pensionDay(boolean active, String note) {
        BranchAlert a = new BranchAlert();
        a.setTitle(active ? "养老金集中发放日" : "养老金发放高峰结束");
        a.setContent(note != null ? note : "今日养老金集中发放，老年客户激增：增开爱心窗口、加强安保维序与现金备付");
        a.setAlertType("PENSION_DAY");
        a.setBizDate(LocalDate.now());
        a.setActive(active);
        a.setCreatedAt(LocalDateTime.now());
        return alerts.save(a);
    }

    @Transactional
    public QueueTicket callback(Long id, String note) {
        QueueTicket t = mustTicket(id);
        t.setCallbackNote(note);
        return tickets.save(t);
    }

    /* ---------------- 定时：长等预警 + 权重老化/预计叫号时间重算 ---------------- */

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void scanLongWait() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(longWaitSeconds);
        for (QueueTicket t : tickets.findByStatusOrderByCreatedAtAsc(Enums.TicketStatus.WAITING)) {
            if (!t.isLongWaitNotified() && t.getCreatedAt().isBefore(threshold)) {
                t.setLongWaitNotified(true);
                tickets.save(t);
                addEvent(t.getId(), Enums.IssueType.LONG_WAIT,
                        "客户已等待超过 " + (longWaitSeconds / 60) + " 分钟，请大堂经理主动安抚并优先安排"
                                + (t.isElderly() ? "（老人客户，立即安排陪同）" : ""),
                        null, null);
            }
        }
        for (QueueTicket t : tickets.findByStatusOrderByCreatedAtAsc(Enums.TicketStatus.ASSIGNED)) {
            LocalDateTime base = t.getAssignedAt() != null ? t.getAssignedAt() : t.getCreatedAt();
            if (!t.isLongWaitNotified() && base.isBefore(threshold)) {
                t.setLongWaitNotified(true);
                tickets.save(t);
                addEvent(t.getId(), Enums.IssueType.LONG_WAIT,
                        "已分流但等待叫号超过 " + (longWaitSeconds / 60) + " 分钟，请大堂经理核查窗口负载",
                        null, null);
            }
        }
    }

    /** 定时老化：每 30 秒重算各台位队列权重与预计叫号时间（等待越久权重越高） */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void refreshQueueEstimates() {
        for (Counter c : counters.findAll()) {
            ops.recomputeCounterQueue(c.getId());
        }
    }

    /* ---------------- 查询 / 看板 / 档案 ---------------- */

    public Map<String, Object> dashboard() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<QueueTicket> all = tickets.findAllByOrderByCreatedAtDesc();
        m.put("totalTickets", all.size());
        m.put("waiting", tickets.countByStatus(Enums.TicketStatus.WAITING));
        m.put("assigned", tickets.countByStatus(Enums.TicketStatus.ASSIGNED));
        m.put("serving", tickets.countByStatus(Enums.TicketStatus.SERVING));
        m.put("pending", tickets.countByStatus(Enums.TicketStatus.PENDING));
        m.put("done", tickets.countByStatus(Enums.TicketStatus.DONE));
        m.put("cancelled", tickets.countByStatus(Enums.TicketStatus.CANCELLED));
        m.put("waitingElderly", tickets.countByStatusAndElderlyTrue(Enums.TicketStatus.WAITING)
                + tickets.countByStatusAndElderlyTrue(Enums.TicketStatus.ASSIGNED)
                + tickets.countByStatusAndElderlyTrue(Enums.TicketStatus.PENDING));
        m.put("fraudPending", tickets.countByFraudStatus(Enums.FraudStatus.PENDING));
        m.put("fraudBlocked", tickets.countByFraudStatus(Enums.FraudStatus.BLOCKED));
        m.put("complaints", tickets.countByComplaintTrue());
        m.put("openIssues", events.countByResolvedFalse());

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long finished = tickets.countFinished(todayStart, LocalDateTime.now());
        long sumWait = tickets.sumWaitSeconds(todayStart, LocalDateTime.now());
        m.put("todayFinished", finished);
        m.put("avgWaitSeconds", finished == 0 ? 0 : sumWait / finished);

        Map<String, Long> channelDist = new LinkedHashMap<>();
        for (Enums.Channel ch : Enums.Channel.values()) {
            channelDist.put(ch.name(), tickets.countByChannel(ch));
        }
        m.put("channelDist", channelDist);
        m.put("cash", todayCash());
        m.put("alerts", alerts.findByActiveTrueOrderByCreatedAtDesc());
        m.put("counters", counters.findAll());
        m.put("staff", users.findAll());
        m.put("openEventList", events.findByResolvedFalseOrderByCreatedAtDesc());
        m.put("largeAmountThreshold", largeAmountThreshold);
        return m;
    }

    public QueueTicket getTicket(Long id) {
        return mustTicket(id);
    }

    public List<BranchAlert> activeAlerts() {
        return alerts.findByActiveTrueOrderByCreatedAtDesc();
    }

    public List<QueueTicket> ticketList() {
        return tickets.findAllByOrderByCreatedAtDesc();
    }

    public List<QueueEvent> eventList(Long ticketId) {
        return ticketId == null ? events.findAllByOrderByCreatedAtDesc()
                : events.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }

    public List<Counter> counterList() { return counters.findAll(); }

    public List<User> staffList() { return users.findAll(); }

    /* ---------------- helpers ---------------- */

    private QueueTicket mustTicket(Long id) {
        return tickets.findById(id).orElseThrow(() -> new BizException("排队记录不存在: " + id));
    }

    private Counter mustCounter(Long id) {
        return counters.findById(id).orElseThrow(() -> new BizException("窗口不存在: " + id));
    }

    private User mustUser(Long id) {
        return users.findById(id).orElseThrow(() -> new BizException("人员不存在: " + id));
    }

    private String resultText(Enums.ResultType r) {
        return switch (r) {
            case SUCCESS -> "业务办理成功";
            case REJECTED -> "业务拒绝/撤回";
            case ESCALATED -> "升级处理";
            case REDIRECTED -> "引导至其他渠道";
        };
    }
}
