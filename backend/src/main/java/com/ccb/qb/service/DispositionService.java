package com.ccb.qb.service;

import com.ccb.qb.dto.Dtos;
import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.*;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 台位异常处置核心服务：
 * 设备离线/转台失败 → 不可篡改处置单（哈希链）→ 前台三方案处置（迁移/转人工/暂缓）
 * → 维修闭环 → 公平恢复建议 → 主管复核 → 统一查询。
 * 全程以同一业务单号（ticketNo）驱动前台、柜台、设备维修、风控四方协同。
 */
@Service
public class DispositionService {

    private final DispositionOrderRepository dispositions;
    private final DispositionActionRepository actions;
    private final RepairOrderRepository repairs;
    private final DeviceEventRepository deviceEvents;
    private final QueueTicketRepository tickets;
    private final CounterRepository counters;
    private final QueueEventRepository events;
    private final RiskAlertRepository riskAlerts;
    private final CustomerNotificationRepository notifications;
    private final QueueTransitionRepository transitions;
    private final QueueOps ops;
    private final RiskService riskService;

    /** 处置单哈希链串行锁（单实例演示环境保证链式完整性） */
    private final Object chainLock = new Object();

    public DispositionService(DispositionOrderRepository dispositions, DispositionActionRepository actions,
                              RepairOrderRepository repairs, DeviceEventRepository deviceEvents,
                              QueueTicketRepository tickets, CounterRepository counters,
                              QueueEventRepository events, RiskAlertRepository riskAlerts,
                              CustomerNotificationRepository notifications,
                              QueueTransitionRepository transitions,
                              QueueOps ops, RiskService riskService) {
        this.dispositions = dispositions;
        this.actions = actions;
        this.repairs = repairs;
        this.deviceEvents = deviceEvents;
        this.tickets = tickets;
        this.counters = counters;
        this.events = events;
        this.riskAlerts = riskAlerts;
        this.notifications = notifications;
        this.transitions = transitions;
        this.ops = ops;
        this.riskService = riskService;
    }

    /* ==================== 设备状态上报（幂等） ==================== */

    /**
     * 设备状态上报。eventId 全局唯一：同一事件反复上报只处理一次；
     * 台位已离线时再次离线上报不会重复生成处置单（不会造成双重叫号或队列丢失）。
     */
    @Transactional
    public DeviceEvent reportDeviceEvent(Dtos.DeviceEventRequest req, User actor) {
        Optional<DeviceEvent> dup = deviceEvents.findByEventId(req.eventId());
        if (dup.isPresent()) {
            return dup.get();   // 幂等：重复上报直接返回首次处理结果
        }
        Counter c = counters.findByIdForUpdate(req.counterId())
                .orElseThrow(() -> new BizException("台位不存在: " + req.counterId()));
        DeviceEvent e = new DeviceEvent();
        e.setEventId(req.eventId());
        e.setCounterId(c.getId());
        e.setStatus(req.status().toUpperCase());
        e.setReason(req.reason());
        e.setReportedBy(actor == null ? "设备自检" : actor.getDisplayName());
        e.setCreatedAt(LocalDateTime.now());

        if ("OFFLINE".equalsIgnoreCase(req.status())) {
            handleOffline(e, c, req.reason(), actor);
        } else if ("ONLINE".equalsIgnoreCase(req.status())) {
            handleOnline(e, c);
        } else {
            throw new BizException("status 仅支持 ONLINE / OFFLINE");
        }
        return deviceEvents.save(e);
    }

    private void handleOffline(DeviceEvent e, Counter c, String reason, User actor) {
        if (c.getStatus() == Enums.CounterStatus.OFFLINE || c.getStatus() == Enums.CounterStatus.FAULT) {
            e.setProcessed(false);
            e.setResultNote("台位已处于离线/故障状态，本次重复上报已忽略，不重复生成处置单");
            return;
        }
        c.setStatus(Enums.CounterStatus.OFFLINE);
        c.setCurrentTicketId(null);
        counters.save(c);

        // 生成维修单（同一台位一次故障事件一单）
        RepairOrder ro = new RepairOrder();
        ro.setRepairNo("WX" + DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDateTime.now())
                + "-" + String.format("%03d", repairs.count() + 1));
        ro.setCounterId(c.getId());
        ro.setCounterName(c.getName());
        ro.setFailureReason(reason == null || reason.isBlank() ? "设备离线" : reason);
        ro.setStatus(Enums.RepairStatus.OPEN);
        ro.setReportedBy(e.getReportedBy());
        ro.setCreatedAt(LocalDateTime.now());
        repairs.save(ro);

        // 受影响单据：已取号且已生成叫号预估（已分配/办理中/挂起）
        List<QueueTicket> affected = tickets.findByCounterIdAndStatusIn(c.getId(),
                        List.of(Enums.TicketStatus.ASSIGNED, Enums.TicketStatus.SERVING, Enums.TicketStatus.PENDING))
                .stream().sorted(Comparator.comparing(QueueTicket::getId)).toList();
        int created = 0;
        for (QueueTicket t : affected) {
            // 业务级幂等：已有未闭环处置单的单据不重复生成
            if (!dispositions.findByTicketIdAndStatusIn(t.getId(), List.of(
                    Enums.DispositionStatus.OPEN, Enums.DispositionStatus.ACTION_TAKEN,
                    Enums.DispositionStatus.RECOVERY_PROPOSED)).isEmpty()) {
                continue;
            }
            QueueTicket locked = tickets.findByIdForUpdate(t.getId()).orElse(t);
            String fromStatus = locked.getStatus().name();
            double wBefore = locked.getQueueWeight();
            LocalDateTime etaBefore = locked.getEstimatedCallAt();
            ops.freezeWait(locked);   // 冻结已等待时长（快照）
            DispositionOrder d = createDisposition(locked, c, Enums.FailureType.DEVICE_OFFLINE,
                    "台位「" + c.getName() + "」设备离线：" + ro.getFailureReason(), ro.getId(), actor);
            locked.setStatus(Enums.TicketStatus.PENDING_ALLOC);
            locked.setPendingReason("原台位「" + c.getName() + "」设备离线，待前台处置（处置单 " + d.getOrderNo() + "）");
            tickets.save(locked);
            ops.transition(locked, Enums.QueueChangeType.DEVICE_PENDING, c.getId(), null,
                    fromStatus, locked.getStatus().name(), wBefore, etaBefore,
                    "设备离线，冻结等待时长并挂起为待分配", e.getReportedBy());
            ops.notify(locked, "APP", "【" + locked.getTicketNo() + "】很抱歉，您的办理窗口设备离线，"
                    + "已生成处置单 " + d.getOrderNo() + "，排队权重与已等待时长已保留，前台将为您重新安排");
            addEvent(locked.getId(), Enums.IssueType.DEVICE_OFFLINE,
                    "台位「" + c.getName() + "」设备离线，生成不可篡改处置单 " + d.getOrderNo()
                            + "（维修单 " + ro.getRepairNo() + "），单据转入待分配", actor, c.getId());
            created++;
        }
        e.setProcessed(true);
        e.setResultNote("设备离线已处理：维修单 " + ro.getRepairNo() + "，生成处置单 " + created + " 张");
    }

    private void handleOnline(DeviceEvent e, Counter c) {
        Optional<RepairOrder> open = repairs.findFirstByCounterIdAndStatusInOrderByCreatedAtDesc(
                c.getId(), List.of(Enums.RepairStatus.OPEN, Enums.RepairStatus.REPAIRING));
        if (open.isPresent()) {
            e.setProcessed(false);
            e.setResultNote("已记录上线心跳；维修单 " + open.get().getRepairNo()
                    + " 未闭环，台位保持离线，待维修完成后由主管复核恢复方案（客户不会自动回队）");
            return;
        }
        if (c.getStatus() == Enums.CounterStatus.OFFLINE || c.getStatus() == Enums.CounterStatus.FAULT) {
            c.setStatus(Enums.CounterStatus.OPEN);
            counters.save(c);
            e.setProcessed(true);
            e.setResultNote("设备恢复在线，台位重新开放");
        } else {
            e.setProcessed(false);
            e.setResultNote("台位本就在线，重复上线心跳已忽略");
        }
    }

    /* ==================== 不可篡改处置单（哈希链） ==================== */

    private DispositionOrder createDisposition(QueueTicket t, Counter original, Enums.FailureType type,
                                               String reason, Long repairId, User actor) {
        DispositionOrder d = new DispositionOrder();
        d.setTicketId(t.getId());
        d.setTicketNo(t.getTicketNo());
        d.setCustomerId(t.getCustomerId());
        d.setCustomerName(t.getCustomerName());
        d.setBusinessType(t.getBusinessType());
        d.setPriority(t.getPriority());
        d.setWeightSnapshot(t.getQueueWeight());
        d.setQueuePosition(original == null ? 0 : ops.positionOf(t));
        d.setOriginalCounterId(original == null ? null : original.getId());
        d.setOriginalCounterName(original == null ? null : original.getName());
        d.setWaitedSeconds(ops.effectiveWaitSeconds(t));
        d.setEstimatedCallAtSnapshot(t.getEstimatedCallAt() == null ? null
                : t.getEstimatedCallAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        d.setFailureType(type);
        d.setFailureReason(reason);
        d.setRepairId(repairId);
        d.setStatus(Enums.DispositionStatus.OPEN);
        synchronized (chainLock) {
            d.setOrderNo(nextOrderNo(t.getTicketNo()));
            // 哈希链时间戳截断到秒：与数据库 timestamp 精度对齐，保证链校验可重算复现
            d.setCreatedAt(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
            String prev = dispositions.findTopByOrderByIdDesc()
                    .map(DispositionOrder::getRecordHash).orElse("GENESIS");
            d.setPrevHash(prev);
            d.setRecordHash(sha256(canonical(d)));
            dispositions.save(d);
        }
        return d;
    }

    private String nextOrderNo(String ticketNo) {
        long n = dispositions.findByTicketNoOrderByCreatedAtAsc(ticketNo).size() + 1;
        String no = "D" + ticketNo + "-" + n;
        // 防御：单号冲突时追加序号（唯一约束兜底）
        while (dispositions.existsByOrderNo(no)) {
            no = "D" + ticketNo + "-" + (++n);
        }
        return no;
    }

    /** 哈希链 canonical 内容：仅覆盖不可篡改快照字段 */
    private String canonical(DispositionOrder d) {
        return String.join("|",
                n(d.getOrderNo()), n(d.getTicketId()), n(d.getTicketNo()), n(d.getCustomerId()),
                n(d.getCustomerName()), n(d.getBusinessType()), n(d.getPriority()),
                n(d.getWeightSnapshot()), n(d.getQueuePosition()), n(d.getOriginalCounterId()),
                n(d.getOriginalCounterName()), n(d.getWaitedSeconds()), n(d.getEstimatedCallAtSnapshot()),
                n(d.getFailureType()), n(d.getFailureReason()), n(d.getRepairId()),
                n(d.getCreatedAt()), n(d.getPrevHash()));
    }

    private static String n(Object o) { return o == null ? "" : String.valueOf(o); }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 全链校验：任何快照字段被篡改都会导致对应记录哈希或链式引用校验失败 */
    public Map<String, Object> verifyChain() {
        List<DispositionOrder> all = dispositions.findAll().stream()
                .sorted(Comparator.comparing(DispositionOrder::getId)).toList();
        String prev = "GENESIS";
        List<Map<String, Object>> broken = new ArrayList<>();
        for (DispositionOrder d : all) {
            boolean ok = d.getPrevHash().equals(prev) && d.getRecordHash().equals(sha256(canonical(d)));
            if (!ok) {
                broken.add(Map.of("orderNo", d.getOrderNo(),
                        "hashMatch", d.getRecordHash().equals(sha256(canonical(d))),
                        "chainMatch", d.getPrevHash().equals(prev)));
            }
            prev = d.getRecordHash();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", all.size());
        r.put("valid", broken.isEmpty());
        r.put("broken", broken);
        r.put("message", broken.isEmpty() ? "哈希链完整，全部处置单快照未被篡改" : "发现 " + broken.size() + " 条记录被篡改或链断裂");
        return r;
    }

    /* ==================== 前台处置：迁移 / 转人工 / 暂缓 ==================== */

    /**
     * 前台处置（幂等：idempotencyKey 唯一约束 + 响应回放）。
     * 迁移：原子释放原台位占用 → 锁定目标台位容量 → 继承等待时长 → 重算预计叫号时间；
     * 目标台位被并发占用时回滚到可解释的待分配状态，不重复排队、不丢失原队列位置。
     */
    @Transactional
    public Map<String, Object> applyAction(Long dispositionId, Dtos.DispositionActionRequest req,
                                           String idemKey, User actor) {
        if (idemKey != null && !idemKey.isBlank()) {
            Optional<DispositionAction> dup = actions.findByIdempotencyKey(idemKey.trim());
            if (dup.isPresent()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("replayed", true);
                r.put("message", "重复提交已忽略（幂等键 " + idemKey.trim() + "），未产生重复动作");
                r.put("disposition", mustDisposition(dispositionId));
                return r;
            }
        }
        DispositionOrder d = mustDisposition(dispositionId);
        if (d.getStatus() == Enums.DispositionStatus.RESOLVED) {
            throw new BizException("处置单 " + d.getOrderNo() + " 已闭环，不可重复处置");
        }
        QueueTicket t = tickets.findByIdForUpdate(d.getTicketId())
                .orElseThrow(() -> new BizException("排队记录不存在: " + d.getTicketId()));
        if (t.getStatus() == Enums.TicketStatus.DONE || t.getStatus() == Enums.TicketStatus.CANCELLED) {
            throw new BizException("客户单据已结束，无法处置");
        }

        return switch (req.actionType()) {
            case MIGRATE -> doMigrate(d, t, req.targetCounterId(), req.note(), idemKey, actor);
            case TO_MANUAL -> doToManual(d, t, req.targetCounterId(), req.note(), idemKey, actor);
            case DEFER -> doDefer(d, t, req.note(), idemKey, actor);
            default -> throw new BizException("前台处置仅支持 MIGRATE / TO_MANUAL / DEFER");
        };
    }

    /** 迁移：保留原排队权重，原子换台 */
    private Map<String, Object> doMigrate(DispositionOrder d, QueueTicket t, Long targetCounterId,
                                          String note, String idemKey, User actor) {
        if (targetCounterId == null) throw new BizException("迁移必须指定目标台位");
        Counter target = counters.findByIdForUpdate(targetCounterId)
                .orElseThrow(() -> new BizException("目标台位不存在: " + targetCounterId));
        String fail = transferPrecheck(t, target);
        if (fail != null) {
            return rollbackToPending(d, t, target, fail, idemKey, actor);
        }
        doTransfer(t, target, actor, "保留原排队权重迁移（处置单 " + d.getOrderNo() + "）"
                + (note == null || note.isBlank() ? "" : "：" + note), Enums.QueueChangeType.MIGRATE);
        d.setStatus(Enums.DispositionStatus.RESOLVED);
        d.setResolvedAt(LocalDateTime.now());
        d.setResolutionNote("已迁移至「" + target.getName() + "」，继承已等待 "
                + d.getWaitedSeconds() + " 秒，预计叫号时间已重算");
        dispositions.save(d);
        recordAction(d, Enums.DispositionActionType.MIGRATE, actor,
                "迁移至「" + target.getName() + "」：原子释放原台位占用并锁定目标容量，"
                        + "继承等待时长 " + d.getWaitedSeconds() + " 秒，权重 " + d.getWeightSnapshot() + " 保留",
                idemKey);
        ops.notify(t, "APP", "【" + t.getTicketNo() + "】已为您迁移至「" + target.getName()
                + "」，排队位置与等待时长不变，请留意新的叫号时间");
        riskService.checkFrequentTransfer(t, d.getId(), actor);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("migrated", true);
        r.put("ticket", t);
        r.put("disposition", d);
        r.put("message", "迁移成功：已锁定「" + target.getName() + "」容量并继承原排队权重");
        return r;
    }

    /** 转人工窗口 */
    private Map<String, Object> doToManual(DispositionOrder d, QueueTicket t, Long targetCounterId,
                                           String note, String idemKey, User actor) {
        Counter target;
        if (targetCounterId != null) {
            target = counters.findByIdForUpdate(targetCounterId)
                    .orElseThrow(() -> new BizException("目标台位不存在: " + targetCounterId));
        } else {
            target = counters.findAll().stream()
                    .filter(c -> c.getStatus() == Enums.CounterStatus.OPEN)
                    .filter(c -> c.getType() != Enums.CounterType.SELF_SERVICE)
                    .filter(c -> ops.supports(c, t.getBusinessType()))
                    .filter(c -> ops.counterLoad(c.getId()) < c.getCapacity())
                    .min(Comparator.comparingDouble(c -> ops.counterLoad(c.getId()) * 1.0 / c.getCapacity()))
                    .map(c -> counters.findByIdForUpdate(c.getId()).orElse(c))
                    .orElse(null);
            if (target == null) {
                return rollbackToPending(d, t, null, "当前无可用人工窗口（均已满员或不可用）", idemKey, actor);
            }
        }
        String fail = transferPrecheck(t, target);
        if (fail != null) {
            return rollbackToPending(d, t, target, fail, idemKey, actor);
        }
        if (target.getType() == Enums.CounterType.SELF_SERVICE) {
            throw new BizException("「转人工窗口」不能选择自助设备，请选择人工台位");
        }
        t.setChannel(Enums.Channel.COUNTER);
        doTransfer(t, target, actor, "转人工窗口（处置单 " + d.getOrderNo() + "）"
                + (note == null || note.isBlank() ? "" : "：" + note), Enums.QueueChangeType.TO_MANUAL);
        d.setStatus(Enums.DispositionStatus.RESOLVED);
        d.setResolvedAt(LocalDateTime.now());
        d.setResolutionNote("已转人工窗口「" + target.getName() + "」");
        dispositions.save(d);
        recordAction(d, Enums.DispositionActionType.TO_MANUAL, actor,
                "转人工窗口「" + target.getName() + "」，继承等待时长 " + d.getWaitedSeconds() + " 秒", idemKey);
        ops.notify(t, "APP", "【" + t.getTicketNo() + "】已为您转至人工窗口「" + target.getName()
                + "」，请前往该窗口等候叫号");
        riskService.checkFrequentTransfer(t, d.getId(), actor);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("migrated", true);
        r.put("ticket", t);
        r.put("disposition", d);
        r.put("message", "已转人工窗口「" + target.getName() + "」");
        return r;
    }

    /** 客户暂缓：保留权重与已等待时长，随时可恢复 */
    private Map<String, Object> doDefer(DispositionOrder d, QueueTicket t, String note,
                                        String idemKey, User actor) {
        ops.freezeWait(t);
        String fromStatus = t.getStatus().name();
        t.setStatus(Enums.TicketStatus.DEFERRED);
        t.setPendingReason("客户暂缓办理，排队权重与已等待 " + t.getAccumulatedWaitSeconds()
                + " 秒已保留，可随时恢复" + (note == null || note.isBlank() ? "" : "（" + note + "）"));
        tickets.save(t);
        ops.transition(t, Enums.QueueChangeType.DEFER, d.getOriginalCounterId(), null,
                fromStatus, t.getStatus().name(), t.getQueueWeight(), t.getEstimatedCallAt(),
                "客户暂缓", actor == null ? "前台" : actor.getDisplayName());
        d.setStatus(Enums.DispositionStatus.RESOLVED);
        d.setResolvedAt(LocalDateTime.now());
        d.setResolutionNote("客户暂缓，权重与等待时长已保留");
        dispositions.save(d);
        recordAction(d, Enums.DispositionActionType.DEFER, actor,
                "客户暂缓：冻结等待计时，排队权重 " + d.getWeightSnapshot() + " 保留", idemKey);
        ops.notify(t, "SMS", "【" + t.getTicketNo() + "】已为您暂缓排队，排队权重与已等待时长已保留，"
                + "返回网点后请联系前台恢复，无需重新取号");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("deferred", true);
        r.put("ticket", t);
        r.put("disposition", d);
        r.put("message", "已暂缓：权重与等待时长保留，客户可随时恢复");
        return r;
    }

    /** 迁移冲突回滚：不重复排队、不丢失原队列位置，进入可解释的待分配状态 */
    private Map<String, Object> rollbackToPending(DispositionOrder d, QueueTicket t, Counter target,
                                                  String failReason, String idemKey, User actor) {
        String fromStatus = t.getStatus().name();
        Long fromCounter = t.getCounterId();
        // 原子释放原台位占用（状态迁出即释放派生容量）
        t.setCounterId(null);
        t.setStatus(Enums.TicketStatus.PENDING_ALLOC);
        t.setPendingReason("迁移目标" + (target == null ? "" : "「" + target.getName() + "」")
                + failReason + "；已回滚至待分配，原队列权重 " + d.getWeightSnapshot()
                + " 与已等待 " + d.getWaitedSeconds() + " 秒完整保留，可重新选择台位");
        tickets.save(t);
        ops.transition(t, Enums.QueueChangeType.ROLLBACK_PENDING, fromCounter,
                target == null ? null : target.getId(), fromStatus, t.getStatus().name(),
                d.getWeightSnapshot(), d.getEstimatedCallAtSnapshot(),
                "迁移冲突回滚：" + failReason, actor == null ? "前台" : actor.getDisplayName());
        recordAction(d, Enums.DispositionActionType.ROLLBACK_PENDING, actor,
                "迁移失败回滚待分配：" + failReason + "（未重复排队，原队列位置信息保留）", idemKey);
        ops.notify(t, "APP", "【" + t.getTicketNo() + "】目标窗口暂时不可用，已为您保留排队位置，"
                + "前台正在重新安排，无需重新取号");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", false);
        r.put("rolledBack", true);
        r.put("reason", failReason);
        r.put("ticket", t);
        r.put("disposition", d);
        r.put("message", "目标台位不可用，已回滚至待分配（原队列权重与等待时长保留）");
        return r;
    }

    /* ==================== 手动转台（转台失败 → 处置单） ==================== */

    /**
     * 手动转台。目标台位不可用（容量/状态/业务能力）时：
     * 生成 TRANSFER_FAILED 不可篡改处置单，单据回滚至可解释的待分配状态。
     */
    @Transactional
    public Map<String, Object> transferTicket(Long ticketId, Dtos.TransferRequest req, User actor) {
        QueueTicket t = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new BizException("排队记录不存在: " + ticketId));
        if (t.getStatus() != Enums.TicketStatus.ASSIGNED
                && t.getStatus() != Enums.TicketStatus.PENDING_ALLOC) {
            throw new BizException("仅「已分配/待分配」单据可转台，当前状态：" + t.getStatus());
        }
        Counter target = counters.findByIdForUpdate(req.targetCounterId())
                .orElseThrow(() -> new BizException("目标台位不存在: " + req.targetCounterId()));
        String fail = transferPrecheck(t, target);
        Map<String, Object> r = new LinkedHashMap<>();
        if (fail != null) {
            Counter original = t.getCounterId() == null ? null
                    : counters.findById(t.getCounterId()).orElse(null);
            String fromStatus = t.getStatus().name();
            Long fromCounter = t.getCounterId();
            ops.freezeWait(t);
            DispositionOrder d = createDisposition(t, original, Enums.FailureType.TRANSFER_FAILED,
                    "转台至「" + target.getName() + "」失败：" + fail
                            + (req.reason() == null || req.reason().isBlank() ? "" : "（" + req.reason() + "）"),
                    null, actor);
            t.setCounterId(null);
            t.setStatus(Enums.TicketStatus.PENDING_ALLOC);
            t.setPendingReason("转台失败：" + fail + "，已回滚至待分配（处置单 " + d.getOrderNo() + "）");
            tickets.save(t);
            ops.transition(t, Enums.QueueChangeType.ROLLBACK_PENDING, fromCounter, target.getId(),
                    fromStatus, t.getStatus().name(), d.getWeightSnapshot(), d.getEstimatedCallAtSnapshot(),
                    "转台失败回滚：" + fail, actor == null ? "系统" : actor.getDisplayName());
            recordAction(d, Enums.DispositionActionType.ROLLBACK_PENDING, actor,
                    "转台失败自动生成处置单：" + fail, null);
            ops.notify(t, "APP", "【" + t.getTicketNo() + "】转台未能完成（" + fail
                    + "），已为您保留排队位置，前台将重新安排");
            addEvent(t.getId(), Enums.IssueType.TRANSFER_FAILED,
                    "转台至「" + target.getName() + "」失败（" + fail + "），生成处置单 " + d.getOrderNo(),
                    actor, target.getId());
            r.put("ok", false);
            r.put("rolledBack", true);
            r.put("reason", fail);
            r.put("disposition", d);
            r.put("ticket", t);
            return r;
        }
        doTransfer(t, target, actor, "手动转台"
                + (req.reason() == null || req.reason().isBlank() ? "" : "：" + req.reason()),
                Enums.QueueChangeType.MIGRATE);
        riskService.checkFrequentTransfer(t, null, actor);
        r.put("ok", true);
        r.put("ticket", t);
        return r;
    }

    /** 转台前校验：返回 null 表示可转，否则返回失败原因 */
    private String transferPrecheck(QueueTicket t, Counter target) {
        if (Objects.equals(t.getCounterId(), target.getId())
                && t.getStatus() == Enums.TicketStatus.ASSIGNED) {
            return "单据已在目标台位，无需重复转台";
        }
        if (target.getStatus() != Enums.CounterStatus.OPEN) {
            return "目标台位当前不可用（" + target.getStatus() + "）";
        }
        if (!ops.supports(target, t.getBusinessType())) {
            return "目标台位不支持业务类型「" + t.getBusinessType() + "」";
        }
        long load = ops.counterLoad(target.getId());
        if (load >= target.getCapacity()) {
            return "目标台位容量已满（" + load + "/" + target.getCapacity() + "）";
        }
        return null;
    }

    /**
     * 原子迁移核心（调用方须已持有目标台位悲观锁与单据悲观锁）：
     * 释放原台位占用 → 锁定目标容量 → 继承等待时长 → 重算预计叫号时间。
     */
    private void doTransfer(QueueTicket t, Counter target, User actor, String reason,
                            Enums.QueueChangeType changeType) {
        Long fromCounter = t.getCounterId();
        String fromStatus = t.getStatus().name();
        double wBefore = t.getQueueWeight();
        LocalDateTime etaBefore = t.getEstimatedCallAt();

        t.setCounterId(target.getId());          // 占用切换到目标台位（原台位占用随之释放）
        t.setStatus(Enums.TicketStatus.ASSIGNED);
        t.setAssignedAt(LocalDateTime.now());
        t.setPendingReason(null);
        ops.resumeWait(t);                        // 继承已等待时长（累积段保留，重新计时当前段）
        t.setTransferCount(t.getTransferCount() + 1);
        tickets.save(t);

        ops.recomputeCounterQueue(target.getId()); // 重算目标台位预计叫号时间
        if (fromCounter != null && !fromCounter.equals(target.getId())) {
            ops.recomputeCounterQueue(fromCounter);
        }
        ops.transition(t, changeType, fromCounter, target.getId(), fromStatus,
                Enums.TicketStatus.ASSIGNED.name(), wBefore, etaBefore, reason,
                actor == null ? "系统" : actor.getDisplayName());
    }

    /** 暂缓恢复：优先回到原台位（可用时），否则进入待分配由前台重新安排 */
    @Transactional
    public QueueTicket resumeTicket(Long ticketId, User actor) {
        QueueTicket t = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new BizException("排队记录不存在: " + ticketId));
        if (t.getStatus() != Enums.TicketStatus.DEFERRED) {
            throw new BizException("仅「客户暂缓」状态的单据可恢复，当前状态：" + t.getStatus());
        }
        Counter original = null;
        DispositionOrder last = dispositions.findByTicketNoOrderByCreatedAtAsc(t.getTicketNo())
                .stream().reduce((a, b) -> b).orElse(null);
        if (last != null && last.getOriginalCounterId() != null) {
            original = counters.findByIdForUpdate(last.getOriginalCounterId()).orElse(null);
        }
        String fromStatus = t.getStatus().name();
        if (original != null && original.getStatus() == Enums.CounterStatus.OPEN
                && ops.supports(original, t.getBusinessType())
                && ops.counterLoad(original.getId()) < original.getCapacity()) {
            doTransfer(t, original, actor, "客户暂缓结束，恢复原台位排队", Enums.QueueChangeType.RESUME);
            ops.notify(t, "APP", "【" + t.getTicketNo() + "】已为您恢复排队（" + original.getName()
                    + "），等待时长已继承，请留意叫号");
        } else {
            t.setStatus(Enums.TicketStatus.PENDING_ALLOC);
            t.setPendingReason("客户暂缓结束，原台位不可用，待前台重新安排（权重与等待时长已保留）");
            tickets.save(t);
            ops.transition(t, Enums.QueueChangeType.RESUME, null, null, fromStatus,
                    t.getStatus().name(), t.getQueueWeight(), t.getEstimatedCallAt(),
                    "暂缓结束，原台位不可用，进入待分配", actor == null ? "系统" : actor.getDisplayName());
            ops.notify(t, "APP", "【" + t.getTicketNo() + "】已为您恢复排队资格，前台正在安排窗口，无需重新取号");
        }
        return t;
    }

    /* ==================== 设备维修闭环 + 公平恢复建议 ==================== */

    @Transactional
    public RepairOrder startRepair(Long repairId, User actor) {
        RepairOrder ro = mustRepair(repairId);
        if (ro.getStatus() == Enums.RepairStatus.DONE) throw new BizException("维修单已闭环");
        if (ro.getStatus() == Enums.RepairStatus.REPAIRING) return ro;   // 幂等：重复开始直接返回
        ro.setStatus(Enums.RepairStatus.REPAIRING);
        ro.setStartedAt(LocalDateTime.now());
        repairs.save(ro);
        recordRepairAction(ro, Enums.DispositionActionType.REPAIR_START, actor,
                "维修人员到场，开始检修「" + ro.getCounterName() + "」");
        return ro;
    }

    /**
     * 维修完成：台位恢复开放，但客户不自动回队——
     * 按故障时权重快照、当前窗口负载、同类业务服务能力生成公平恢复建议，待主管复核。
     */
    @Transactional
    public Map<String, Object> completeRepair(Long repairId, String note, User actor) {
        RepairOrder ro = mustRepair(repairId);
        if (ro.getStatus() == Enums.RepairStatus.DONE) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("repair", ro);
            r.put("proposed", 0);
            r.put("message", "维修单已闭环（重复提交已忽略）");
            return r;
        }
        Counter c = counters.findByIdForUpdate(ro.getCounterId()).orElseThrow();
        ro.setStatus(Enums.RepairStatus.DONE);
        ro.setCompletedAt(LocalDateTime.now());
        ro.setResultNote(note == null || note.isBlank() ? "设备修复，自检通过" : note);
        repairs.save(ro);
        c.setStatus(Enums.CounterStatus.OPEN);
        counters.save(c);
        recordRepairAction(ro, Enums.DispositionActionType.REPAIR_DONE, actor,
                "维修完成：「" + ro.getCounterName() + "」恢复开放；" + ro.getResultNote());

        int proposed = 0;
        List<Map<String, Object>> suggestions = new ArrayList<>();
        for (DispositionOrder d : dispositions.findByRepairId(ro.getId())) {
            if (d.getStatus() != Enums.DispositionStatus.OPEN
                    && d.getStatus() != Enums.DispositionStatus.ACTION_TAKEN) continue;
            QueueTicket t = tickets.findByIdForUpdate(d.getTicketId()).orElse(null);
            if (t == null || (t.getStatus() != Enums.TicketStatus.PENDING_ALLOC
                    && t.getStatus() != Enums.TicketStatus.DEFERRED)) continue;   // 前台已处置
            Map<String, Object> suggestion = buildRecoverySuggestion(d, t);
            d.setRecoverySuggestion(toJson(suggestion));
            d.setStatus(Enums.DispositionStatus.RECOVERY_PROPOSED);
            dispositions.save(d);
            recordAction(d, Enums.DispositionActionType.RECOVERY_PROPOSE, actor,
                    "维修完成，按权重快照/窗口负载/同类业务能力生成公平恢复建议（不自动回队，待主管复核）", null);
            ops.notify(t, "APP", "【" + t.getTicketNo() + "】原办理窗口设备已修复，"
                    + "正在为您核定公平恢复方案，请留意叫号安排");
            suggestions.add(suggestion);
            proposed++;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("repair", ro);
        r.put("proposed", proposed);
        r.put("suggestions", suggestions);
        r.put("message", proposed == 0
                ? "维修完成，台位已开放；无待恢复客户（均已由前台处置）"
                : "维修完成，已生成 " + proposed + " 条公平恢复建议，待主管复核（客户未自动回队）");
        return r;
    }

    /** 公平恢复建议：故障时权重快照 + 当前窗口负载 + 同类业务服务能力 → 建议插入位置（非队首） */
    private Map<String, Object> buildRecoverySuggestion(DispositionOrder d, QueueTicket t) {
        List<Counter> candidates = counters.findAll().stream()
                .filter(c -> c.getStatus() == Enums.CounterStatus.OPEN)
                .filter(c -> ops.supports(c, t.getBusinessType()))
                .filter(c -> ops.counterLoad(c.getId()) < c.getCapacity())
                .sorted(Comparator.comparingDouble((Counter c) -> ops.counterLoad(c.getId()) * 1.0 / c.getCapacity())
                        .thenComparingLong(c -> ops.counterLoad(c.getId())))
                .toList();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("ticketNo", t.getTicketNo());
        s.put("weightSnapshot", d.getWeightSnapshot());
        s.put("waitedSecondsAtFailure", d.getWaitedSeconds());
        if (candidates.isEmpty()) {
            s.put("type", "NONE");
            s.put("reason", "当前无可用同类业务台位（均已满员或离线），建议保持待分配并通知客户稍候");
            return s;
        }
        Counter best = candidates.get(0);
        long load = ops.counterLoad(best.getId());
        // 按故障时权重快照确定插入位置：排在权重更高的在排单据之后（不插队首）
        long ahead = ops.counterQueue(best.getId()).stream()
                .filter(x -> x.getQueueWeight() > d.getWeightSnapshot()).count();
        int position = (int) ahead + 1;
        LocalDateTime eta = LocalDateTime.now().plusSeconds((long) position * Math.max(60, best.getAvgServeSeconds()));
        s.put("type", "REJOIN");
        s.put("counterId", best.getId());
        s.put("counterName", best.getName());
        s.put("position", position);
        s.put("eta", eta.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        s.put("load", load);
        s.put("capacity", best.getCapacity());
        s.put("reason", "依据故障时权重快照 " + d.getWeightSnapshot()
                + "、台位当前负载 " + load + "/" + best.getCapacity()
                + "、同类业务（" + t.getBusinessType() + "）服务能力，建议插入「" + best.getName()
                + "」第 " + position + " 位（按权重公平排序，非队首）");
        return s;
    }

    /* ==================== 主管复核 ==================== */

    @Transactional
    public Map<String, Object> reviewRecovery(Long dispositionId, Dtos.ReviewRequest req, User supervisor) {
        DispositionOrder d = mustDisposition(dispositionId);
        if (d.getStatus() != Enums.DispositionStatus.RECOVERY_PROPOSED) {
            throw new BizException("仅「待复核」状态的恢复建议可复核，当前状态：" + d.getStatus());
        }
        QueueTicket t = tickets.findByIdForUpdate(d.getTicketId())
                .orElseThrow(() -> new BizException("排队记录不存在: " + d.getTicketId()));
        Map<String, Object> r = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(req.approve())) {
            Long targetId = req.counterId() != null ? req.counterId() : suggestedCounterId(d);
            if (targetId == null) throw new BizException("恢复建议无可用台位，请指定改派台位或退回");
            Counter target = counters.findByIdForUpdate(targetId)
                    .orElseThrow(() -> new BizException("目标台位不存在: " + targetId));
            String fail = transferPrecheck(t, target);
            if (fail != null) {
                throw new BizException("按建议恢复失败：" + fail + "（建议已保留，请改派台位或稍后重试）");
            }
            doTransfer(t, target, supervisor,
                    "主管复核通过，按公平恢复建议回队（权重快照 " + d.getWeightSnapshot() + "）",
                    Enums.QueueChangeType.RECOVER);
            d.setStatus(Enums.DispositionStatus.RESOLVED);
            d.setResolvedAt(LocalDateTime.now());
            d.setResolutionNote("主管复核通过，恢复至「" + target.getName() + "」第 "
                    + ops.positionOf(t) + " 位" + (req.note() == null || req.note().isBlank() ? "" : "；" + req.note()));
            dispositions.save(d);
            recordAction(d, Enums.DispositionActionType.REVIEW_APPROVE, supervisor,
                    "复核通过：恢复至「" + target.getName() + "」（按权重快照公平插入，未插队首）"
                            + (req.note() == null || req.note().isBlank() ? "" : "；" + req.note()), null);
            ops.notify(t, "APP", "【" + t.getTicketNo() + "】设备已修复，已为您恢复至「" + target.getName()
                    + "」排队（第 " + ops.positionOf(t) + " 位），等待时长已继承，请留意叫号");
            r.put("ok", true);
            r.put("approved", true);
            r.put("ticket", t);
            r.put("position", ops.positionOf(t));
        } else {
            d.setStatus(Enums.DispositionStatus.OPEN);   // 退回前台重新处置
            d.setResolutionNote("主管退回恢复建议：" + (req.note() == null ? "未说明" : req.note()));
            dispositions.save(d);
            t.setPendingReason("主管退回恢复建议（" + (req.note() == null ? "未说明" : req.note())
                    + "），待前台重新处置");
            tickets.save(t);
            recordAction(d, Enums.DispositionActionType.REVIEW_REJECT, supervisor,
                    "复核退回：" + (req.note() == null || req.note().isBlank() ? "未说明" : req.note()), null);
            ops.notify(t, "APP", "【" + t.getTicketNo() + "】您的恢复方案正在重新核定，请稍候");
            r.put("ok", true);
            r.put("approved", false);
        }
        r.put("disposition", d);
        return r;
    }

    private Long suggestedCounterId(DispositionOrder d) {
        try {
            String json = d.getRecoverySuggestion();
            if (json == null) return null;
            String key = "\"counterId\":";
            int i = json.indexOf(key);
            if (i < 0) return null;
            int j = i + key.length();
            int k = j;
            while (k < json.length() && Character.isDigit(json.charAt(k))) k++;
            return Long.parseLong(json.substring(j, k));
        } catch (Exception e) {
            return null;
        }
    }

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append("\"").append(String.valueOf(v).replace("\"", "'")).append("\"");
        }
        return sb.append("}").toString();
    }

    /* ==================== 统一查询 ==================== */

    /** 同一业务单号的统一视图：处置记录/维修闭环/客户通知/队列变更时间线/主管复核/风控告警 */
    public Map<String, Object> trace(String ticketNo) {
        QueueTicket t = tickets.findByTicketNo(ticketNo)
                .orElseThrow(() -> new BizException("业务单号不存在: " + ticketNo));
        List<DispositionOrder> ds = dispositions.findByTicketNoOrderByCreatedAtAsc(ticketNo);
        List<Map<String, Object>> dispViews = new ArrayList<>();
        Set<Long> repairIds = new LinkedHashSet<>();
        List<Map<String, Object>> reviews = new ArrayList<>();
        for (DispositionOrder d : ds) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("disposition", d);
            List<DispositionAction> acts = actions.findByDispositionIdOrderByCreatedAtAsc(d.getId());
            v.put("actions", acts);
            dispViews.add(v);
            if (d.getRepairId() != null) repairIds.add(d.getRepairId());
            acts.stream().filter(a -> a.getActionType() == Enums.DispositionActionType.REVIEW_APPROVE
                    || a.getActionType() == Enums.DispositionActionType.REVIEW_REJECT)
                    .forEach(a -> reviews.add(Map.of(
                            "orderNo", d.getOrderNo(), "action", a.getActionType().name(),
                            "actor", a.getActorName() == null ? "" : a.getActorName(),
                            "detail", a.getDetail() == null ? "" : a.getDetail(),
                            "time", a.getCreatedAt() == null ? "" : a.getCreatedAt().toString())));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ticket", t);
        r.put("dispositions", dispViews);
        r.put("repairs", repairIds.stream().map(id -> repairs.findById(id).orElse(null)).filter(Objects::nonNull).toList());
        r.put("notifications", notifications.findByTicketNoOrderByCreatedAtAsc(ticketNo));
        r.put("timeline", transitions.findByTicketNoOrderByCreatedAtAsc(ticketNo));
        r.put("riskAlerts", riskAlerts.findByTicketNoOrderByCreatedAtAsc(ticketNo));
        r.put("reviews", reviews);
        r.put("events", events.findByTicketIdOrderByCreatedAtAsc(t.getId()));
        return r;
    }

    /* ==================== 风控告警 ==================== */

    @Transactional
    public RiskAlert ackAlert(Long alertId, String note, User actor) {
        RiskAlert a = riskAlerts.findById(alertId).orElseThrow(() -> new BizException("告警不存在: " + alertId));
        if (a.getStatus() == Enums.RiskAlertStatus.ACKNOWLEDGED) return a;   // 幂等
        a.setStatus(Enums.RiskAlertStatus.ACKNOWLEDGED);
        a.setAckBy(actor == null ? "风控" : actor.getDisplayName());
        a.setAckNote(note);
        a.setAckAt(LocalDateTime.now());
        return riskAlerts.save(a);
    }

    /* ==================== 查询辅助 ==================== */

    public DispositionOrder mustDisposition(Long id) {
        return dispositions.findById(id).orElseThrow(() -> new BizException("处置单不存在: " + id));
    }

    private RepairOrder mustRepair(Long id) {
        return repairs.findById(id).orElseThrow(() -> new BizException("维修单不存在: " + id));
    }

    private void recordAction(DispositionOrder d, Enums.DispositionActionType type, User actor,
                              String detail, String idemKey) {
        DispositionAction a = new DispositionAction();
        a.setDispositionId(d.getId());
        a.setOrderNo(d.getOrderNo());
        a.setTicketNo(d.getTicketNo());
        a.setActionType(type);
        a.setActorId(actor == null ? null : actor.getId());
        a.setActorName(actor == null ? "系统" : actor.getDisplayName());
        a.setActorRole(actor == null ? null : actor.getRole());
        a.setDetail(detail);
        a.setIdempotencyKey(idemKey == null || idemKey.isBlank() ? null : idemKey.trim());
        a.setCreatedAt(LocalDateTime.now());
        actions.save(a);
    }

    private void recordRepairAction(RepairOrder ro, Enums.DispositionActionType type, User actor, String detail) {
        // 维修动作记录到该维修单关联的所有未闭环处置单上，保证同一业务单号可追溯
        List<DispositionOrder> related = dispositions.findByRepairId(ro.getId());
        if (related.isEmpty()) {
            DispositionAction a = new DispositionAction();
            a.setDispositionId(0L);
            a.setOrderNo(ro.getRepairNo());
            a.setTicketNo("-");
            a.setActionType(type);
            a.setActorId(actor == null ? null : actor.getId());
            a.setActorName(actor == null ? "设备维修" : actor.getDisplayName());
            a.setActorRole(actor == null ? Enums.Role.REPAIR : actor.getRole());
            a.setDetail(detail);
            a.setCreatedAt(LocalDateTime.now());
            actions.save(a);
            return;
        }
        for (DispositionOrder d : related) {
            recordAction(d, type, actor, detail, null);
        }
    }

    private QueueEvent addEvent(Long ticketId, Enums.IssueType type, String content, User actor, Long counterId) {
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
