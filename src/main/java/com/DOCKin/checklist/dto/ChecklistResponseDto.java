package com.DOCKin.checklist.dto;

import com.DOCKin.checklist.model.Checklist;
import com.DOCKin.checklist.model.ChecklistItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "체크리스트 상세 res dto (관리자용)")
public class ChecklistResponseDto {

    @Schema(description = "체크리스트 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer checklistId;

    @Schema(description = "장비 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long equipmentId;

    @Schema(description = "체크리스트 제목", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @Schema(description = "점검 단계 (PRE/POST)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String phase;

    @Schema(description = "체크리스트 항목 목록", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<ChecklistItemSimpleResponseDto> items;

    @Schema(description = "생성 시각")
    private LocalDateTime createdAt;

    @Schema(description = "수정 시각")
    private LocalDateTime updatedAt;

    public static ChecklistResponseDto fromEntity(Checklist checklist, List<ChecklistItem> items) {
        return ChecklistResponseDto.builder()
                .checklistId(checklist.getChecklistId())
                .equipmentId(checklist.getEquipment().getEquipmentId())
                .title(checklist.getTitle())
                .phase(checklist.getPhase().name())
                .items(items.stream()
                        .map(ChecklistItemSimpleResponseDto::fromEntity)
                        .collect(Collectors.toList()))
                .createdAt(checklist.getCreatedAt())
                .updatedAt(checklist.getUpdatedAt())
                .build();
    }
}
