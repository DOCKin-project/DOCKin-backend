package com.DOCKin.checklist.dto;

import com.DOCKin.checklist.model.ChecklistPhase;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "체크리스트 생성 req dto")
public class ChecklistCreateRequestDto {

    @Schema(description = "장비 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "장비 번호는 필수입니다.")
    private Long equipmentId;

    @Schema(description = "체크리스트 제목", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "체크리스트 제목은 필수입니다.")
    private String title;

    @Schema(description = "점검 단계 (PRE: 작업 전, POST: 작업 후)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "점검 단계(PRE/POST)는 필수입니다.")
    private ChecklistPhase phase;

    @Schema(description = "체크리스트 항목 목록", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "항목을 최소 1개 이상 등록해야 합니다.")
    @Valid
    private List<ChecklistItemRequestDto> items;
}
