package com.DOCKin.worklog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "작업일지 승인/반려 req dto")
public class WorkLogDecisionRequestDto {

    /** {@code review_comment varchar(255)}. 넘치면 DB가 500을 내기 전에 400으로. */
    @Size(max = 255)
    @Schema(description = "승인/반려 사유 코멘트")
    private String comment;
}
