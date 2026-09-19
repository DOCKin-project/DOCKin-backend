package com.DOCKin.checklist.dto;

import com.DOCKin.checklist.model.ChecklistPhase;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "점검 회차 열기 req dto — QR/NFC로 장비를 찍은 직후")
public class ChecklistRunOpenRequestDto {
    @Schema(description = "장비 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "장비 번호는 필수입니다.")
    private Long equipmentId;

    @Schema(description = "점검 단계 (PRE: 작업 전, POST: 작업 후)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "점검 단계(PRE/POST)는 필수입니다.")
    private ChecklistPhase phase;
}
