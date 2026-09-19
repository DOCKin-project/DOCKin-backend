package com.DOCKin.checklist.dto;

import com.DOCKin.checklist.model.Checklist;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "체크리스트 점검 현황 res dto (사용자용)")
public class ChecklistDetailResponseDto {

    @Schema(description = "체크리스트 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer checklistId;

    @Schema(description = "장비 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long equipmentId;

    @Schema(description = "체크리스트 제목", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @Schema(description = "점검 단계 (PRE/POST)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phase;

    /**
     * 내 열린 회차(ADR-0011). 있으면 {@code items}는 그 회차의 상태이고, 없으면 전부 미체크다.
     * 예전에는 템플릿 전역의 최신 결과였다 — 남의 체크가 보였다. 앱은 이 값으로 "이어서 할지"를 안다.
     */
    @Schema(description = "내 열린 점검 회차 id (없으면 null). items는 이 회차 기준이다")
    private Long myOpenRunId;

    @Schema(description = "항목별 점검 현황 — myOpenRunId가 있으면 그 회차, 없으면 전부 미체크", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<ChecklistItemStatusResponseDto> items;

    public static ChecklistDetailResponseDto of(Checklist checklist, Long myOpenRunId, List<ChecklistItemStatusResponseDto> items) {
        return ChecklistDetailResponseDto.builder()
                .checklistId(checklist.getChecklistId())
                .equipmentId(checklist.getEquipment().getEquipmentId())
                .title(checklist.getTitle())
                .phase(checklist.getPhase().name())
                .myOpenRunId(myOpenRunId)
                .items(items)
                .build();
    }
}
