package com.DOCKin.checklist.dto;

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
@Schema(description = "체크리스트 항목 체크/해제 req dto")
public class ChecklistCheckRequestDto {

    @Schema(description = "점검 여부", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "점검 여부는 필수입니다.")
    private Boolean isChecked;
}
