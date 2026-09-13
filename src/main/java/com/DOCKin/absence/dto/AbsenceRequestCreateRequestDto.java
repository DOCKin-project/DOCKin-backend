package com.DOCKin.absence.dto;

import com.DOCKin.absence.model.AbsenceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "휴가 신청 req dto")
public class AbsenceRequestCreateRequestDto {

    @Schema(description = "휴가 종류 (VACATION/SICK)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "휴가 종류는 필수입니다.")
    private AbsenceType type;

    @Schema(description = "시작일", example = "2026-07-10", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "시작일은 필수입니다.")
    private LocalDate startDate;

    @Schema(description = "종료일", example = "2026-07-12", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "종료일은 필수입니다.")
    private LocalDate endDate;

    @Schema(description = "사유", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "사유는 필수입니다.")
    private String reason;
}
