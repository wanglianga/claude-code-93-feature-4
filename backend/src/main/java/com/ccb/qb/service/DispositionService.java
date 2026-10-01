package com.ccb.qb.service;

import com.ccb.qb.entity.*;
import com.ccb.qb.model.Enums;
import com.ccb.qb.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 特性4核心：设备离线/转台失败处置。
 * <ul>
 *   <li>设备状态上报幂等防抖动；故障瞬间冻结权重快照并生成不可篡改处置单（哈希链）</li>
 *   <li>前台三方案：保留原排队权重迁移 / 转人工窗口 / 客户暂缓；
 *       迁移原子完成“释放原台位 + 锁定目标容量 + 继承等待 + 重算预计叫号”，
 *       目标被占用时回滚到可解释的待分配状态，不重复排队、不丢失队列位置</li>
 *   <li>维修闭环后按权重快照+窗口负载+同类业务服务能力给出公平恢复建议，主管复核后应用</li>
 * </ul>
 */
@Service
public class DispositionService {

    private static final List<Enums.TicketStatus> OCCUPYING =
            List.of(Enums.TicketStatus.ASSIGNED, Enums.TicketStatus.SERVING);
    private static final List<Enums.TicketStatus> DISPOSABLE =
            List.of(Enums.TicketStatus.DISPOSING, Enums.TicketStatus.PENDING_ASSIGN, Enums.TicketStatus.DEFERRED);

    private final DispositionOrderRepository dispositions;
    private final DispositionActionRepository actions;
    private final MaintenanceOrderRepository maintenance;
    private final RecoverySuggestionRepository suggestions;
    private final SupervisorReviewRepository reviews;
    private final DeviceStatusReportRepository reports;
    private final ChainStateRepository chain;
    private final QueueTicketRepository tickets;
    private final CounterRepository counters;
    private final QueueEventRepository events;
    private final NotificationRepository notifications;
    private final RiskAlertRepository riskAlerts;
    private final QueueMath queueMath;
    private final EventLog eventLog;
    private final NotificationService notificationService;
    private final RiskService riskService;

    public DispositionService(DispositionOrderRepository dispositions, DispositionActionRepository actions,
                              MaintenanceOrderRepository maintenance, RecoverySuggestionRepository suggestions,
                              SupervisorReviewRepository reviews, DeviceStatusReportRepository reports,
                              ChainStateRepository chain, QueueTicketRepository tickets,
                              CounterRepository counters, QueueEventRepository events,
                              NotificationRepository notifications, RiskAlertRepository riskAlerts,
                              QueueMath queueMath, EventLog eventLog,
                              NotificationService notificationService, RiskService riskService) {
        this.dispositions = dispositions;
        this.actions = actions;
        this.maintenance = maintenance;
        this.suggestions = suggestions;
        this.reviews = reviews;
        this.reports = reports;
        this.chain = chain;
        this.tickets = tickets;
        this.counters = counters;
        this.events = events;
        this.notifications = notifications;
        this.riskAlerts = riskAlerts;
        this.queueMath = queueMath;
        this.eventLog = eventLog;
        this.notificationService = notificationService;
        this.riskService = riskService;
    }

    /* ==================== 设备状态上报（幂等 + 防抖动） ==================== */

    /**
     * 设备状态上报：reportKey 唯一保证同一上报重复提交只生效一次；
     * 状态反复上报（抖动）时仅在实际变化时应用，其余记 NOOP。
     * 进入离线/故障：释放台位占用、冻结在队客户权重快照、生成处置单与维修单；
     * 恢复开放：自动闭环维修单并生成公平恢复建议。
     */
    @Transactional
    public DeviceStatusReport reportDeviceStatus(Long counterId, Enums.CounterStatus newStatus,
                                                 String reportKey, String note, User actor) {
        if (reportKey == null || reportKey.isBlank()) {
            throw new BizException("缺少设备上报幂等键 reportKey");
        }
        Optional<DeviceStatusReport> dup = reports.findByReportKey(reportKey);
        if (dup.isPresent()) {
            return dup.get(); // 幂等命中：重复提交不产生二次效果
        }

        Counter c = counters.findForUpdate(counterId)
                .orElseThrow(() -> new BizException("台位不存在: " + counterId));
        Enums.CounterStatus old = c.getStatus();

        DeviceStatusReport rec = new DeviceStatusReport();
        rec.setCounterId(counterId);
        rec.setReportKey(reportKey);
        rec.setReportedStatus(newStatus);
        rec.setPreviousStatus(old);
        rec.setNote(note);
        rec.setActorName(actor == null ? "设备自监控" : actor.getDisplayName());
        rec.setCreatedAt(LocalDateTime.now());

        if (old == newStatus) {
            rec.setResult(Enums.DeviceReportResult.NOOP); // 抖动：状态未变化
            return reports.save(rec);
        }

        boolean toOffline = newStatus == Enums.CounterStatus.OFFLINE || newStatus == Enums.CounterStatus.FAULT;
        boolean fromOffline = old == Enums.CounterStatus.OFFLINE || old == Enums.CounterStatus.FAULT;

        c.setStatus(newStatus);
        if (toOffline) {
            // 原子释放台位占用
            c.setCurrentTicketId(null);
            counters.save(c);
            // 同一台位同一时段仅一张未闭环维修单（防抖动重复建单）
            MaintenanceOrder mo = maintenance
                    .findFirstByCounterIdAndStatusInOrderByIdDesc(c.getId(),
                            List.of(Enums.MaintenanceStatus.OPEN, Enums.MaintenanceStatus.REPAIRING))
                    .orElseGet(() -> createMaintenance(c, newStatus, note, actor));
            // 为在队/在办客户生成处置单（权重快照冻结）
            Enums.FailureType ft = Enums.FailureType.DEVICE_OFFLINE;
            String reason = (newStatus == Enums.CounterStatus.OFFLINE ? "设备离线" : "设备故障")
                    + (note == null || note.isBlank() ? "" : "：" + note);
            for (QueueTicket t : tickets.findByCounterIdAndStatusIn(c.getId(), OCCUPYING)) {
                freezeAndCreateDisposition(t, c, mo, ft, reason, actor);
            }
        } else if (fromOffline && newStatus == Enums.CounterStatus.OPEN) {
            counters.save(c);
            // 设备恢复：闭环未完成的维修单并生成公平恢复建议
            maintenance.findFirstByCounterIdAndStatusInOrderByIdDesc(c.getId(),
                            List.of(Enums.MaintenanceStatus.OPEN, Enums.MaintenanceStatus.REPAIRING))
                    .ifPresent(mo -> completeMaintenance(mo, actor));
        } else {
            counters.save(c);
        }
        rec.setResult(Enums.DeviceReportResult.APPLIED);
        return reports.save(rec);
    }

    private MaintenanceOrder createMaintenance(Counter c, Enums.CounterStatus failureStatus, String note, User actor) {
        ChainState cs = chainState();
        cs.setMaintenanceSeq(cs.getMaintenanceSeq() + 1);
        chain.save(cs);
        MaintenanceOrder mo = new MaintenanceOrder();
        mo.setOrderNo("WX" + String.format("%06d", cs.getMaintenanceSeq()));
        mo.setCounterId(c.getId());
        mo.setCounterName(c.getName());
        mo.setFailureStatus(failureStatus);
        mo.setFaultDesc(note);
        mo.setReportedBy(actor == null ? "设备自监控" : actor.getDisplayName());
        mo.setStatus(Enums.MaintenanceStatus.OPEN);
        mo.setCreatedAt(LocalDateTime.now());
        return maintenance.save(mo);
    }

    /* ==================== 处置单生成（不可篡改，哈希链） ==================== */

    /**
     * 冻结票据权重并生成处置单。同一票据已有未闭环处置单时直接复用（幂等），
     * 设备状态反复上报/并发上报不会重复生成。
     */
    private DispositionOrder freezeAndCreateDisposition(QueueTicket t, Counter originalCounter,
                                                        MaintenanceOrder mo, Enums.FailureType failureType,
                                                        String reason, User actor) {
        Optional<DispositionOrder> existing =
                dispositions.findFirstByTicketIdAndStatusOrderByIdDesc(t.getId(), Enums.DispositionStatus.OPEN);
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        long waited = queueMath.effectiveWaitSeconds(t, now);
        long weight = queueMath.weight(t, now);
        int ahead = originalCounter == null ? 0
                : (int) tickets.findByCounterIdAndStatusIn(originalCounter.getId(),
                        List.of(Enums.TicketStatus.ASSIGNED)).stream()
                        .filter(x -> !x.getId().equals(t.getId()))
                        .filter(x -> queueMath.weight(x, now) > weight)
                        .count();

        // 冻结票据：等待时长继承 + 权重快照 + 进入处置中
        t.setInheritedWaitSeconds(waited);
        t.setWaitStartedAt(now);
        t.setWeightSnapshot(weight);
        t.setStatus(Enums.TicketStatus.DISPOSING);
        tickets.save(t);

        ChainState cs = chainState();
        DispositionOrder o = new DispositionOrder();
        o.setOrderNo("CZ" + String.format("%06d", cs.getSeq() + 1));
        o.setBizOrderNo(t.getTicketNo());
        o.setTicketId(t.getId());
        o.setMaintenanceOrderId(mo == null ? null : mo.getId());
        o.setCustomerId(t.getCustomerId());
        o.setCustomerName(t.getCustomerName());
        o.setBusinessType(t.getBusinessType());
        o.setPriorityScore(t.getPriorityScore());
        o.setOriginalCounterId(originalCounter == null ? null : originalCounter.getId());
        o.setOriginalCounterName(originalCounter == null ? null : originalCounter.getName());
        o.setWaitedSeconds(waited);
        o.setWeightSnapshot(weight);
        o.setFailureType(failureType);
        o.setFailureReason(reason);
        o.setQueueAhead(ahead);
        o.setStatus(Enums.DispositionStatus.OPEN);
        o.setCreatedAt(now);
        o.setPrevHash(cs.getLastHash());
        o.setHash(computeHash(o));

        cs.setSeq(cs.getSeq() + 1);
        cs.setLastHash(o.getHash());
        chain.save(cs);
        dispositions.save(o);

        eventLog.log(t.getId(),
                failureType == Enums.FailureType.DEVICE_OFFLINE
                        ? Enums.IssueType.DEVICE_OFFLINE : Enums.IssueType.TRANSFER_FAILED,
                "生成处置单[" + o.getOrderNo() + "]：" + reason
                        + "；已等待 " + waited + " 秒、权重快照 " + weight + " 已冻结，排队权益完整保留",
                actor, originalCounter == null ? null : originalCounter.getId());
        notificationService.notify(t, Enums.NotifyChannel.APP,
                "很抱歉，" + (originalCounter == null ? "服务台位" : originalCounter.getName())
                        + " 出现异常，您的号码 " + t.getTicketNo() + " 已进入处置流程，"
                        + "已等待 " + waited + " 秒的排队权益已为您保留，请留意后续安排");
        return o;
    }

    /** 处置单内容哈希（SHA-256，链式）：任何内容篡改都会导致校验失败 */
    public static String computeHash(DispositionOrder o) {
        String canonical = String.join("|",
                o.getPrevHash(), o.getOrderNo(), o.getBizOrderNo(), String.valueOf(o.getTicketId()),
                o.getCustomerName(), o.getBusinessType().name(), String.valueOf(o.getPriorityScore()),
                String.valueOf(o.getOriginalCounterId()), String.valueOf(o.getWaitedSeconds()),
                String.valueOf(o.getWeightSnapshot()), o.getFailureType().name(), o.getFailureReason(),
                String.valueOf(o.getQueueAhead()), o.getCreatedAt().toString());
        return sha256(canonical);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ChainState chainState() {
        return chain.findForUpdate().orElseGet(() -> {
            ChainState c = new ChainState();
            c.setId(1L);
            c.setLastHash("GENESIS");
            return chain.save(c);
        });
    }

    /* ==================== 前台三方案处置 ==================== */

    /** 方案一：保留原排队权重后迁移 */
    @Transactional
    public DispositionAction migrate(Long orderId, Long targetCounterId, String idempotencyKey, User actor) {
        return doTransfer(orderId, targetCounterId, idempotencyKey, actor,
                Enums.DispositionActionType.MIGRATE_KEEP_WEIGHT);
    }

    /** 方案二：转人工窗口 */
    @Transactional
    public DispositionAction toManual(Long orderId, Long targetCounterId, String idempotencyKey, User actor) {
        return doTransfer(orderId, targetCounterId, idempotencyKey, actor,
                Enums.DispositionActionType.TO_MANUAL);
    }

    /**
     * 原子迁移：同一事务内完成 释放原台位占用 → 锁定目标台位容量 → 继承等待时长 → 重算预计叫号。
     * 目标台位在操作期间被其他请求占用（容量满）时，回滚到可解释的待分配状态：
     * 不重复排队（状态守卫 + 幂等键）、不丢失原队列位置（权重快照与继承等待完整保留）。
     */
    private DispositionAction doTransfer(Long orderId, Long targetCounterId, String idempotencyKey,
                                         User actor, Enums.DispositionActionType type) {
        DispositionOrder order = dispositions.findForUpdate(orderId)
                .orElseThrow(() -> new BizException("处置单不存在: " + orderId));
        // 幂等检查置于悲观锁之后：并发重复提交在锁内串行，重复请求命中同一动作记录
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<DispositionAction> dup = actions.findByIdempotencyKey(idempotencyKey);
            if (dup.isPresent()) return dup.get();
        }
        QueueTicket t = mustTicket(order.getTicketId());
        if (!DISPOSABLE.contains(t.getStatus())) {
            // 状态守卫：已被处置/已叫号的票据不会再次入队（防重复排队）
            throw new BizException("当前状态不可执行迁移：" + t.getStatus());
        }
        Counter target = counters.findForUpdate(targetCounterId)
                .orElseThrow(() -> new BizException("目标台位不存在: " + targetCounterId));
        if (target.getStatus() != Enums.CounterStatus.OPEN) {
            throw new BizException("目标台位当前不可用（" + target.getStatus() + "），请选择其他台位");
        }
        if (type == Enums.DispositionActionType.TO_MANUAL
                && target.getType() == Enums.CounterType.SELF_SERVICE) {
            throw new BizException("转人工窗口需选择人工台位（非自助设备）");
        }
        if (type == Enums.DispositionActionType.MIGRATE_KEEP_WEIGHT
                && !queueMath.supports(t.getChannel(), target)) {
            throw new BizException("目标台位不具备同类业务服务能力，请选择支持该业务的台位");
        }

        LocalDateTime now = LocalDateTime.now();
        Long fromCounterId = t.getCounterId();
        long active = tickets.countByCounterIdAndStatusIn(target.getId(), OCCUPYING);

        if (active >= target.getCapacity()) {
            // —— 业务回滚：目标台位被其他请求占用，转入可解释的待分配状态
            t.setStatus(Enums.TicketStatus.PENDING_ASSIGN);
            t.setCounterId(null);
            t.setInheritedWaitSeconds(queueMath.effectiveWaitSeconds(t, now)); // 等待持续累计
            t.setWaitStartedAt(now);
            tickets.save(t);
            DispositionAction a = saveAction(order, t, type, Enums.DispositionActionResult.ROLLED_BACK,
                    fromCounterId, null,
                    "目标台位「" + target.getName() + "」容量已满（" + active + "/" + target.getCapacity()
                            + "），迁移回滚：排队权重快照与已等待时长完整保留，转入待分配，可重新选择台位",
                    idempotencyKey, actor, null, null);
            eventLog.log(t.getId(), Enums.IssueType.PENDING_ASSIGN,
                    "迁移回滚至待分配：" + a.getDetail(), actor, target.getId());
            notificationService.notify(t, Enums.NotifyChannel.APP,
                    "您迁移的目标窗口「" + target.getName() + "」暂时繁忙，已为您保留排队位置与等待时长，"
                            + "工作人员将尽快为您重新安排，无需重新取号");
            return a;
        }

        // —— 原子迁移：释放原台位占用
        releaseOriginalOccupation(t, fromCounterId);

        // —— 锁定目标台位容量（悲观锁内计数）+ 继承等待时长
        long waited = queueMath.effectiveWaitSeconds(t, now);
        t.setInheritedWaitSeconds(waited);
        t.setWaitStartedAt(now);
        t.setCounterId(target.getId());
        t.setStatus(Enums.TicketStatus.ASSIGNED);
        t.setAssignedAt(now);
        t.setTransferCount(t.getTransferCount() + 1);

        // —— 重算预计叫号时间（按继承权重在目标队列中的实际排位）
        long w = queueMath.weight(t, now);
        List<QueueTicket> waiting = waitingAt(target.getId(), t.getId());
        t.setEstimatedCallAt(queueMath.estimateCallAt(target, waiting, w, now));
        tickets.save(t);

        order.setStatus(Enums.DispositionStatus.RESOLVED);
        dispositions.save(order);

        String label = type == Enums.DispositionActionType.TO_MANUAL ? "转人工窗口" : "保留原排队权重迁移";
        DispositionAction a = saveAction(order, t, type, Enums.DispositionActionResult.SUCCESS,
                fromCounterId, target.getId(),
                label + "至「" + target.getName() + "」：继承已等待 " + waited + " 秒，当前权重 " + w,
                idempotencyKey, actor, t.getEstimatedCallAt(), waited);
        eventLog.log(t.getId(), Enums.IssueType.QUEUE_MIGRATED,
                a.getDetail() + "，预计叫号时间 " + t.getEstimatedCallAt(), actor, target.getId());
        notificationService.notify(t, Enums.NotifyChannel.APP,
                "您的号码 " + t.getTicketNo() + " 已" + label + "「" + target.getName()
                        + "」，等待时长已保留，预计叫号时间 " + t.getEstimatedCallAt());
        riskService.checkFrequentTransfer(t, fromCounterId, target.getId(), actor);
        return a;
    }

    /** 方案三：客户暂缓（冻结等待时长，释放占用，可随时恢复迁移） */
    @Transactional
    public DispositionAction defer(Long orderId, String note, String idempotencyKey, User actor) {
        DispositionOrder order = dispositions.findForUpdate(orderId)
                .orElseThrow(() -> new BizException("处置单不存在: " + orderId));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<DispositionAction> dup = actions.findByIdempotencyKey(idempotencyKey);
            if (dup.isPresent()) return dup.get();
        }
        QueueTicket t = mustTicket(order.getTicketId());
        if (t.getStatus() != Enums.TicketStatus.DISPOSING
                && t.getStatus() != Enums.TicketStatus.PENDING_ASSIGN) {
            throw new BizException("当前状态不可暂缓：" + t.getStatus());
        }
        LocalDateTime now = LocalDateTime.now();
        Long fromCounterId = t.getCounterId();
        releaseOriginalOccupation(t, fromCounterId);

        // 暂缓：等待时长冻结为继承值（不丢失也不虚增），恢复时继续累计
        t.setInheritedWaitSeconds(queueMath.effectiveWaitSeconds(t, now));
        t.setWaitStartedAt(null);
        t.setCounterId(null);
        t.setStatus(Enums.TicketStatus.DEFERRED);
        tickets.save(t);

        order.setStatus(Enums.DispositionStatus.RESOLVED);
        dispositions.save(order);

        DispositionAction a = saveAction(order, t, Enums.DispositionActionType.DEFER,
                Enums.DispositionActionResult.SUCCESS, fromCounterId, null,
                "客户暂缓：已等待 " + t.getInheritedWaitSeconds() + " 秒已冻结保留"
                        + (note == null || note.isBlank() ? "" : "（" + note + "）"),
                idempotencyKey, actor, null, t.getInheritedWaitSeconds());
        eventLog.log(t.getId(), Enums.IssueType.CUSTOMER_DEFERRED, a.getDetail(), actor, null);
        notificationService.notify(t, Enums.NotifyChannel.APP,
                "您的号码 " + t.getTicketNo() + " 已按您的要求暂缓，排队等待时长已为您冻结保留，"
                        + "返回网点后请告知工作人员恢复排队，无需重新取号");
        return a;
    }

    /**
     * 直接转台尝试（转台失败触发处置单的场景）：
     * 目标台位可用 → 原子迁移；不可用 → 生成 TRANSFER_FAILED 处置单并进入处置流程。
     */
    @Transactional
    public DispositionAction transferNow(Long ticketId, Long targetCounterId, String idempotencyKey, User actor) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<DispositionAction> dup = actions.findByIdempotencyKey(idempotencyKey);
            if (dup.isPresent()) return dup.get();
        }
        QueueTicket t = mustTicket(ticketId);
        if (t.getStatus() != Enums.TicketStatus.ASSIGNED && t.getStatus() != Enums.TicketStatus.WAITING) {
            throw new BizException("当前状态不可转台：" + t.getStatus());
        }
        Counter target = counters.findForUpdate(targetCounterId)
                .orElseThrow(() -> new BizException("目标台位不存在: " + targetCounterId));
        LocalDateTime now = LocalDateTime.now();
        Long fromCounterId = t.getCounterId();
        long active = tickets.countByCounterIdAndStatusIn(target.getId(), OCCUPYING);
        boolean targetBusy = target.getStatus() != Enums.CounterStatus.OPEN || active >= target.getCapacity();

        if (targetBusy) {
            // —— 转台失败：生成不可篡改处置单，进入四方协同处置流程
            Counter orig = fromCounterId == null ? null : counters.findById(fromCounterId).orElse(null);
            String reason = "转台失败：目标台位「" + target.getName() + "」"
                    + (target.getStatus() != Enums.CounterStatus.OPEN
                            ? "不可用（" + target.getStatus() + "）" : "容量已满（" + active + "/" + target.getCapacity() + "）");
            DispositionOrder o = freezeAndCreateDisposition(t, orig, null,
                    Enums.FailureType.TRANSFER_FAILED, reason, actor);
            DispositionAction a = saveAction(o, t, Enums.DispositionActionType.MIGRATE_KEEP_WEIGHT,
                    Enums.DispositionActionResult.ROLLED_BACK, fromCounterId, target.getId(),
                    reason + "，已转入处置流程，排队权益完整保留", idempotencyKey, actor, null, null);
            return a;
        }

        // —— 转台成功：原子迁移（与处置迁移同一套并发语义）
        releaseOriginalOccupation(t, fromCounterId);
        long waited = queueMath.effectiveWaitSeconds(t, now);
        t.setInheritedWaitSeconds(waited);
        t.setWaitStartedAt(now);
        t.setCounterId(target.getId());
        t.setStatus(Enums.TicketStatus.ASSIGNED);
        t.setAssignedAt(now);
        t.setTransferCount(t.getTransferCount() + 1);
        long w = queueMath.weight(t, now);
        t.setEstimatedCallAt(queueMath.estimateCallAt(target, waitingAt(target.getId(), t.getId()), w, now));
        tickets.save(t);
        DispositionAction a = saveAction(null, t, Enums.DispositionActionType.MIGRATE_KEEP_WEIGHT,
                Enums.DispositionActionResult.SUCCESS, fromCounterId, target.getId(),
                "直接转台至「" + target.getName() + "」：继承已等待 " + waited + " 秒",
                idempotencyKey, actor, t.getEstimatedCallAt(), waited);
        eventLog.log(t.getId(), Enums.IssueType.QUEUE_MIGRATED, a.getDetail(), actor, target.getId());
        riskService.checkFrequentTransfer(t, fromCounterId, target.getId(), actor);
        return a;
    }

    /* ==================== 维修闭环与公平恢复 ==================== */

    @Transactional
    public MaintenanceOrder startRepair(Long maintenanceId, User actor) {
        MaintenanceOrder mo = mustMaintenance(maintenanceId);
        if (mo.getStatus() == Enums.MaintenanceStatus.DONE) {
            throw new BizException("维修单已闭环");
        }
        if (mo.getStatus() == Enums.MaintenanceStatus.OPEN) {
            mo.setStatus(Enums.MaintenanceStatus.REPAIRING);
            mo.setStartedAt(LocalDateTime.now());
            mo.setAssigneeId(actor == null ? null : actor.getId());
            maintenance.save(mo);
        }
        return mo;
    }

    /** 维修完成：设备恢复开放，为仍未安置的受影响客户生成公平恢复建议（不自动插回队首） */
    @Transactional
    public List<RecoverySuggestion> finishRepair(Long maintenanceId, User actor) {
        MaintenanceOrder mo = mustMaintenance(maintenanceId);
        if (mo.getStatus() == Enums.MaintenanceStatus.DONE) {
            return suggestions.findByMaintenanceOrderIdOrderByIdAsc(maintenanceId); // 幂等
        }
        Counter c = counters.findForUpdate(mo.getCounterId())
                .orElseThrow(() -> new BizException("台位不存在: " + mo.getCounterId()));
        c.setStatus(Enums.CounterStatus.OPEN);
        c.setNote("维修完成，恢复开放");
        counters.save(c);
        return completeMaintenance(mo, actor);
    }

    private List<RecoverySuggestion> completeMaintenance(MaintenanceOrder mo, User actor) {
        mo.setStatus(Enums.MaintenanceStatus.DONE);
        mo.setFinishedAt(LocalDateTime.now());
        maintenance.save(mo);

        List<RecoverySuggestion> result = new ArrayList<>();
        for (DispositionOrder o : dispositions.findByMaintenanceOrderIdOrderByIdAsc(mo.getId())) {
            QueueTicket t = tickets.findById(o.getTicketId()).orElse(null);
            if (t == null || !DISPOSABLE.contains(t.getStatus())) continue;      // 已被前台妥善处置
            if (suggestions.existsByTicketIdAndStatus(t.getId(), Enums.SuggestionStatus.PENDING)) continue;
            RecoverySuggestion s = computeFairSuggestion(t, o, mo);
            result.add(s);
            eventLog.log(t.getId(), Enums.IssueType.RECOVERY_SUGGESTED,
                    "维修完成，生成公平恢复建议[" + s.getId() + "]：建议恢复至「" + s.getSuggestedCounterName()
                            + "」第 " + s.getSuggestedPosition() + " 位（按故障时权重快照 " + o.getWeightSnapshot()
                            + " 计算，未自动插回队首），待主管复核",
                    actor, mo.getCounterId());
        }
        return result;
    }

    /**
     * 公平恢复建议：故障时权重快照 + 当前窗口负载 + 同类业务服务能力。
     * 建议位置 = 按权重快照在目标队列中的实际排位（不默认插回队首）。
     */
    private RecoverySuggestion computeFairSuggestion(QueueTicket t, DispositionOrder o, MaintenanceOrder mo) {
        LocalDateTime now = LocalDateTime.now();
        long w = t.getWeightSnapshot() != null ? t.getWeightSnapshot() : queueMath.weight(t, now);

        List<Map<String, Object>> candidateInfos = new ArrayList<>();
        Counter best = null;
        double bestLoad = Double.MAX_VALUE;
        int bestServe = Integer.MAX_VALUE;
        for (Counter c : counters.findAll()) {
            if (c.getStatus() != Enums.CounterStatus.OPEN) continue;
            if (!queueMath.supports(t.getChannel(), c)) continue;   // 同类业务服务能力
            long activeCount = tickets.countByCounterIdAndStatusIn(c.getId(), OCCUPYING);
            double load = c.getCapacity() <= 0 ? 1.0 : (double) activeCount / c.getCapacity();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("counterId", c.getId());
            info.put("name", c.getName());
            info.put("active", activeCount);
            info.put("capacity", c.getCapacity());
            info.put("avgServeSeconds", c.getAvgServeSeconds());
            info.put("loadPct", Math.round(load * 100));
            candidateInfos.add(info);
            // 负载最低优先，其次同类业务办理效率更高（均件时长更短）
            if (load < bestLoad || (load == bestLoad && c.getAvgServeSeconds() < bestServe)) {
                best = c;
                bestLoad = load;
                bestServe = c.getAvgServeSeconds();
            }
        }
        if (best == null) {
            throw new BizException("当前无可用台位生成恢复建议，请稍后再试");
        }
        int position = queueMath.positionOf(best, waitingAt(best.getId(), t.getId()), w, now);

        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("policy", "按故障发生时的权重快照插入对应排位，不自动插回队首");
        reason.put("weightSnapshot", w);
        reason.put("waitedSecondsAtFailure", o.getWaitedSeconds());
        reason.put("candidates", candidateInfos);
        reason.put("chosenCounterId", best.getId());
        reason.put("chosenReason", "负载最低（" + Math.round(bestLoad * 100) + "%）且具备同类业务服务能力");
        reason.put("position", position);
        reason.put("ahead", position - 1);

        RecoverySuggestion s = new RecoverySuggestion();
        s.setBizOrderNo(t.getTicketNo());
        s.setTicketId(t.getId());
        s.setDispositionOrderId(o.getId());
        s.setMaintenanceOrderId(mo.getId());
        s.setSuggestedCounterId(best.getId());
        s.setSuggestedCounterName(best.getName());
        s.setSuggestedPosition(position);
        try {
            s.setReasonJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(reason));
        } catch (Exception e) {
            s.setReasonJson(reason.toString());
        }
        s.setStatus(Enums.SuggestionStatus.PENDING);
        s.setCreatedAt(now);
        return suggestions.save(s);
    }

    /** 主管复核：批准 → 应用恢复（容量不足则转入待分配）；驳回 → 转入待分配由前台重新处置 */
    @Transactional
    public SupervisorReview review(Long suggestionId, Enums.ReviewDecision decision, String comment, User supervisor) {
        RecoverySuggestion s = suggestions.findById(suggestionId)
                .orElseThrow(() -> new BizException("恢复建议不存在: " + suggestionId));
        if (s.getStatus() != Enums.SuggestionStatus.PENDING) {
            // 幂等：重复复核返回首次结果
            return reviews.findBySuggestionIdOrderByIdDesc(suggestionId).stream().findFirst()
                    .orElseThrow(() -> new BizException("该建议已被处理"));
        }
        QueueTicket t = mustTicket(s.getTicketId());
        LocalDateTime now = LocalDateTime.now();

        SupervisorReview r = new SupervisorReview();
        r.setSuggestionId(s.getId());
        r.setBizOrderNo(s.getBizOrderNo());
        r.setReviewerId(supervisor.getId());
        r.setReviewerName(supervisor.getDisplayName());
        r.setDecision(decision);
        r.setComment(comment);
        r.setCreatedAt(now);
        reviews.save(r);

        if (decision == Enums.ReviewDecision.APPROVE) {
            applyRecovery(s, t, supervisor, now);
        } else {
            s.setStatus(Enums.SuggestionStatus.REJECTED);
            suggestions.save(s);
            t.setStatus(Enums.TicketStatus.PENDING_ASSIGN);
            t.setCounterId(null);
            t.setWaitStartedAt(now);
            tickets.save(t);
            eventLog.log(t.getId(), Enums.IssueType.PENDING_ASSIGN,
                    "主管驳回恢复建议[" + s.getId() + "]：" + (comment == null ? "" : comment)
                            + "，转入待分配，由前台重新处置", supervisor, null);
            notificationService.notify(t, Enums.NotifyChannel.APP,
                    "您的排队恢复方案正在由工作人员重新安排，等待时长持续保留，请留意叫号屏");
        }
        return r;
    }

    private void applyRecovery(RecoverySuggestion s, QueueTicket t, User supervisor, LocalDateTime now) {
        Counter target = counters.findForUpdate(s.getSuggestedCounterId())
                .orElseThrow(() -> new BizException("台位不存在: " + s.getSuggestedCounterId()));
        long active = tickets.countByCounterIdAndStatusIn(target.getId(), OCCUPYING);
        if (target.getStatus() != Enums.CounterStatus.OPEN || active >= target.getCapacity()) {
            // 复核通过但应用时容量不足：可解释的待分配，不强行插队
            s.setStatus(Enums.SuggestionStatus.APPROVED);
            suggestions.save(s);
            t.setStatus(Enums.TicketStatus.PENDING_ASSIGN);
            t.setCounterId(null);
            t.setWaitStartedAt(now);
            tickets.save(t);
            eventLog.log(t.getId(), Enums.IssueType.PENDING_ASSIGN,
                    "恢复建议[" + s.getId() + "]已批准，但目标台位「" + target.getName() + "」当前容量已满（"
                            + active + "/" + target.getCapacity() + "），转入待分配", supervisor, target.getId());
            return;
        }
        releaseOriginalOccupation(t, t.getCounterId());
        long waited = queueMath.effectiveWaitSeconds(t, now);
        t.setInheritedWaitSeconds(waited);
        t.setWaitStartedAt(now);
        t.setCounterId(target.getId());
        t.setStatus(Enums.TicketStatus.ASSIGNED);
        t.setAssignedAt(now);
        long w = queueMath.weight(t, now);
        t.setEstimatedCallAt(queueMath.estimateCallAt(target, waitingAt(target.getId(), t.getId()), w, now));
        tickets.save(t);

        s.setStatus(Enums.SuggestionStatus.APPLIED);
        suggestions.save(s);
        dispositions.findById(s.getDispositionOrderId()).ifPresent(o -> {
            o.setStatus(Enums.DispositionStatus.RECOVERED);
            dispositions.save(o);
        });
        eventLog.log(t.getId(), Enums.IssueType.RECOVERY_APPLIED,
                "主管复核通过，按公平恢复建议恢复至「" + target.getName() + "」第 " + s.getSuggestedPosition()
                        + " 位（故障权重快照 " + (t.getWeightSnapshot() == null ? w : t.getWeightSnapshot())
                        + "），预计叫号时间 " + t.getEstimatedCallAt(), supervisor, target.getId());
        notificationService.notify(t, Enums.NotifyChannel.APP,
                "设备已修复，您的号码 " + t.getTicketNo() + " 已按排队权益恢复至「" + target.getName()
                        + "」，预计叫号时间 " + t.getEstimatedCallAt());
    }

    /* ==================== 查询 ==================== */

    public List<DispositionOrder> listDispositions(Enums.DispositionStatus status) {
        return status == null ? dispositions.findAllByOrderByIdDesc()
                : dispositions.findByStatusOrderByIdDesc(status);
    }

    public DispositionOrder getDisposition(Long id) {
        return dispositions.findById(id).orElseThrow(() -> new BizException("处置单不存在: " + id));
    }

    public List<DispositionAction> listActions(Long dispositionOrderId) {
        return dispositionOrderId == null ? actions.findAll()
                : actions.findByDispositionOrderIdOrderByIdAsc(dispositionOrderId);
    }

    public List<MaintenanceOrder> listMaintenance(Enums.MaintenanceStatus status) {
        return status == null ? maintenance.findAllByOrderByIdDesc()
                : maintenance.findByStatusOrderByIdDesc(status);
    }

    public List<RecoverySuggestion> listSuggestions(Enums.SuggestionStatus status) {
        return status == null ? suggestions.findAllByOrderByIdDesc()
                : suggestions.findByStatusOrderByIdDesc(status);
    }

    /** 哈希链完整性校验：任何处置单内容被篡改都会定位到具体单号 */
    public Map<String, Object> verifyChain() {
        List<DispositionOrder> all = dispositions.findAllByOrderByIdAsc();
        String prev = "GENESIS";
        List<String> broken = new ArrayList<>();
        for (DispositionOrder o : all) {
            if (!Objects.equals(o.getPrevHash(), prev) || !Objects.equals(o.getHash(), computeHash(o))) {
                broken.add(o.getOrderNo());
            }
            prev = o.getHash();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", all.size());
        m.put("valid", broken.isEmpty());
        m.put("brokenOrders", broken);
        return m;
    }

    /** 统一查询：同一业务单号下的处置单/处置记录/维修闭环/客户通知/队列变更时间线/主管复核/风控告警 */
    public Map<String, Object> trace(String bizOrderNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        QueueTicket ticket = tickets.findByTicketNo(bizOrderNo).stream().findFirst().orElse(null);
        m.put("ticket", ticket);
        List<DispositionOrder> orders = dispositions.findByBizOrderNoOrderByIdAsc(bizOrderNo);
        m.put("dispositions", orders);
        m.put("actions", actions.findByBizOrderNoOrderByIdAsc(bizOrderNo));
        m.put("events", ticket == null ? List.of()
                : events.findByTicketIdOrderByCreatedAtAsc(ticket.getId()));
        m.put("notifications", notifications.findByBizOrderNoOrderByIdAsc(bizOrderNo));
        List<Long> moIds = orders.stream().map(DispositionOrder::getMaintenanceOrderId)
                .filter(Objects::nonNull).distinct().toList();
        m.put("maintenance", moIds.isEmpty() ? List.of() : maintenance.findAllById(moIds));
        m.put("suggestions", suggestions.findByBizOrderNoOrderByIdAsc(bizOrderNo));
        m.put("reviews", reviews.findByBizOrderNoOrderByIdAsc(bizOrderNo));
        m.put("riskAlerts", riskAlerts.findByBizOrderNoOrderByIdAsc(bizOrderNo));
        m.put("chain", verifyChain());
        return m;
    }

    /* ==================== helpers ==================== */

    private void releaseOriginalOccupation(QueueTicket t, Long fromCounterId) {
        if (fromCounterId == null) return;
        counters.findById(fromCounterId).ifPresent(orig -> {
            if (Objects.equals(orig.getCurrentTicketId(), t.getId())) {
                orig.setCurrentTicketId(null);
                counters.save(orig);
            }
        });
    }

    private List<QueueTicket> waitingAt(Long counterId, Long excludeTicketId) {
        return tickets.findByCounterIdAndStatusIn(counterId, List.of(Enums.TicketStatus.ASSIGNED))
                .stream().filter(x -> !x.getId().equals(excludeTicketId)).toList();
    }

    private DispositionAction saveAction(DispositionOrder order, QueueTicket t,
                                         Enums.DispositionActionType type, Enums.DispositionActionResult result,
                                         Long fromCounterId, Long toCounterId, String detail,
                                         String idempotencyKey, User actor,
                                         LocalDateTime estimatedCallAt, Long inheritedWait) {
        DispositionAction a = new DispositionAction();
        a.setDispositionOrderId(order == null ? null : order.getId());
        a.setBizOrderNo(t.getTicketNo());
        a.setActionType(type);
        a.setResult(result);
        a.setFromCounterId(fromCounterId);
        a.setToCounterId(toCounterId);
        a.setTicketStatusAfter(t.getStatus());
        a.setEstimatedCallAtAfter(estimatedCallAt);
        a.setInheritedWaitSeconds(inheritedWait);
        a.setDetail(detail);
        a.setIdempotencyKey(idempotencyKey == null || idempotencyKey.isBlank()
                ? "auto-" + UUID.randomUUID() : idempotencyKey);
        a.setActorId(actor == null ? null : actor.getId());
        a.setActorName(actor == null ? "系统" : actor.getDisplayName());
        a.setCreatedAt(LocalDateTime.now());
        return actions.save(a);
    }

    private QueueTicket mustTicket(Long id) {
        return tickets.findById(id).orElseThrow(() -> new BizException("排队记录不存在: " + id));
    }

    private MaintenanceOrder mustMaintenance(Long id) {
        return maintenance.findById(id).orElseThrow(() -> new BizException("维修单不存在: " + id));
    }
}
