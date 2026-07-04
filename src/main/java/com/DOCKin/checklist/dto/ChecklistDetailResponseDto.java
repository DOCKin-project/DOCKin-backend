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

    @Schema(description = "항목별 점검 현황", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<ChecklistItemStatusResponseDto> items;

    public static ChecklistDetailResponseDto of(Checklist checklist, List<ChecklistItemStatusResponseDto> items) {
        return ChecklistDetailResponseDto.builder()
                .checklistId(checklist.getChecklistId())
                .equipmentId(checklist.getEquipment().getEquipmentId())
                .title(checklist.getTitle())
                .phase(checklist.getPhase().name())
                .items(items)
                .build();
    }
}
