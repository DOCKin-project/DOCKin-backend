package com.DOCKin.absence.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "휴가 신청 승인/거절 req dto")
public class AbsenceDecisionRequestDto {

    @Schema(description = "승인/거절 사유 코멘트")
    private String comment;
}
