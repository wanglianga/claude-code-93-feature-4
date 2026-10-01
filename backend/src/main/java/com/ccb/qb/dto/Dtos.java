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
            /** 适老安排 **/
            boolean escort,
            boolean priorityWindow,
            boolean preReview,
            boolean familyConfirm,
            String familyContact,
            /** 紧急业务（如紧急挂失/医疗缴费），风控告警不阻断 */
            boolean urgent,
            /** 已完成身份核验 */
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

    /* ---------------- 台位异常处置（设备离线/转台失败） ---------------- */

    /** 设备状态上报（eventId 为设备侧幂等键） */
    public record DeviceEventRequest(@NotBlank String eventId, @NotNull Long counterId,
                                     @NotBlank String status, String reason) {}

    /** 前台处置动作：MIGRATE / TO_MANUAL / DEFER */
    public record DispositionActionRequest(@NotNull Enums.DispositionActionType actionType,
                                           Long targetCounterId, String note) {}

    /** 主管复核 */
    public record ReviewRequest(@NotNull Boolean approve, Long counterId, String note) {}

    /** 手动转台 */
    public record TransferRequest(@NotNull Long targetCounterId, String reason) {}

    /** 维修完成/确认备注 */
    public record NoteRequest(String note) {}
}
