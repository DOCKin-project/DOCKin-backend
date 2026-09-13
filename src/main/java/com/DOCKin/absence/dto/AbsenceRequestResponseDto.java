package com.DOCKin.absence.dto;

import com.DOCKin.absence.model.AbsenceRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "휴가 신청 상세 res dto")
public class AbsenceRequestResponseDto {

    @Schema(description = "신청 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer requestId;

    @Schema(description = "신청자 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @Schema(description = "휴가 종류 (VACATION/SICK)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String type;

    @Schema(description = "시작일", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate startDate;

    @Schema(description = "종료일", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate endDate;

    @Schema(description = "사유", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;

    @Schema(description = "증빙서류 URL")
    private String documentUrl;

    @Schema(description = "처리 상태 (PENDING/APPROVED/REJECTED)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;

    @Schema(description = "신청 시각", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime requestedAt;

    @Schema(description = "처리한 관리자 id")
    private String processedBy;

    @Schema(description = "처리 시각")
    private LocalDateTime processedAt;

    @Schema(description = "승인/거절 사유 코멘트")
    private String decisionComment;

    public static AbsenceRequestResponseDto fromEntity(AbsenceRequest request) {
        return AbsenceRequestResponseDto.builder()
                .requestId(request.getRequestId())
                .userId(request.getMember().getUserId())
                .type(request.getType().name())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .reason(request.getReason())
                .documentUrl(request.getDocumentUrl())
                .status(request.getStatus().name())
                .requestedAt(request.getRequestedAt())
                .processedBy(request.getProcessedBy() != null ? request.getProcessedBy().getUserId() : null)
                .processedAt(request.getProcessedAt())
                .decisionComment(request.getDecisionComment())
                .build();
    }
}
