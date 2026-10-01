package com.ccb.qb.dto;

import com.ccb.qb.model.Enums;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** 请求/响应 DTO 集合（record 不可变对象） */
public final class Dtos {
    private Dtos() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResponse(String token, Long id, String username, String displayName,
                                String role, String skill, Long counterId) {}

    /** 客户取号/预约 */
    public record TicketRequest(
            Long customerId,
            String customerName,
            @NotNull Enums.CustomerType customerType,
            @NotNull Enums.Source source,
            @NotNull Enums.BusinessType businessType,
            @NotNull Enums.RiskLevel riskLevel,
            boolean elderly,
            boolean wheelchair,
            boolean hearingAssist,
            Integer estimatedMinutes,
            LocalDateTime appointmentTime,
            Long amount,
            /** 适老安排 */
            boolean escort,
            boolean priorityWindow,
            boolean preReview,
            boolean familyConfirm,
            String familyContact,
            /** 特性4：取号幂等键（重复提交返回同一单据） */
            String idempotencyKey,
            /** 特性4：紧急业务 */
            boolean urgent,
            /** 特性4：身份已完成现场核验 */
            boolean identityVerified
    ) {}

    /** 大堂经理分流 */
    public record AssignRequest(
            @NotNull Enums.Channel channel,
            Long counterId,
            Long assigneeId,
            Enums.Material material,
            String materialNote,
            boolean escort,
            boolean priorityWindow,
            boolean preReview,
            boolean familyConfirm
    ) {}

    /** 协同事件上报 */
    public record EventRequest(
            Long ticketId,
            @NotNull Enums.IssueType type,
            @NotBlank String content,
            Long counterId
    ) {}

    public record EventResolveRequest(@NotBlank String resolution, boolean resumeTicket) {}

    /** 反诈核验 */
    public record FraudRequest(@NotNull Enums.FraudStatus fraudStatus, String fraudNote) {}

    /** 办理结束 */
    public record CompleteRequest(
            @NotNull Enums.ResultType resultType,
            String resultNote,
            String riskNotice,
            boolean complaint,
            String complaintNote
    ) {}

    public record CallbackRequest(String callbackNote) {}

    public record CounterStatusRequest(@NotNull Enums.CounterStatus status, String note) {}

    public record CashUpdateRequest(Long balance, Long threshold, Boolean replenishing, String note) {}

    public record AlertRequest(@NotBlank String title, String content, @NotBlank String alertType) {}

    /* ================= 特性4：设备离线/转台失败处置 ================= */

    /** 设备状态上报（幂等键 reportKey 防重复提交与状态抖动） */
    public record DeviceStatusReportRequest(
            @NotNull Enums.CounterStatus status,
            @NotBlank String reportKey,
            String note
    ) {}

    /** 前台处置：迁移（保留原排队权重）/ 转人工窗口 */
    public record MigrateRequest(@NotNull Long targetCounterId, @NotBlank String idempotencyKey) {}

    /** 前台处置：客户暂缓 */
    public record DeferRequest(String note, @NotBlank String idempotencyKey) {}

    /** 直接转台尝试（转台失败触发处置单） */
    public record TransferRequest(@NotNull Long targetCounterId, @NotBlank String idempotencyKey) {}

    /** 主管复核恢复建议 */
    public record ReviewRequest(@NotNull Enums.ReviewDecision decision, String comment) {}

    /** 人工插队调整（产生风控告警） */
    public record JumpRequest(String reason) {}
}
