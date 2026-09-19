package com.DOCKin.checklist.dto;

import com.DOCKin.checklist.model.Checklist;
import com.DOCKin.checklist.model.ChecklistRun;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "점검 회차 res dto — 회차 하나와 그 안의 항목별 상태")
public class ChecklistRunResponseDto {
    @Schema(description = "회차 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long runId;

    @Schema(description = "체크리스트(템플릿) 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer checklistId;

    @Schema(description = "장비 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long equipmentId;

    @Schema(description = "체크리스트 제목", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @Schema(description = "점검 단계 (PRE/POST)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phase;

    @Schema(description = "점검자 사원번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @Schema(description = "IN_PROGRESS / COMPLETED / ABANDONED", requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;

    @Schema(description = "회차 시작 시각", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime startedAt;

    @Schema(description = "닫힌 시각 (열려 있으면 null)")
    private LocalDateTime closedAt;

    @Schema(description = "항목별 상태 — 이 회차 안에서의 최신 체크. 목록 조회에서는 비어 있다")
    private List<ChecklistItemStatusResponseDto> items;

    public static ChecklistRunResponseDto of(ChecklistRun run, List<ChecklistItemStatusResponseDto> items) {
        Checklist checklist = run.getChecklist();
        return ChecklistRunResponseDto.builder()
                .runId(run.getRunId())
                .checklistId(checklist.getChecklistId())
                .equipmentId(checklist.getEquipment().getEquipmentId())
                .title(checklist.getTitle())
                .phase(checklist.getPhase().name())
                .userId(run.getMember().getUserId())
                .status(run.status())
                .startedAt(run.getStartedAt())
                .closedAt(run.getClosedAt())
                .items(items)
                .build();
    }
}
