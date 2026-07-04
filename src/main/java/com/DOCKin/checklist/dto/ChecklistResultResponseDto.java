package com.DOCKin.checklist.dto;

import com.DOCKin.checklist.model.ChecklistResult;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "체크리스트 항목 점검 기록 res dto")
public class ChecklistResultResponseDto {

    @Schema(description = "점검 기록 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer resultId;

    @Schema(description = "항목 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer itemId;

    @Schema(description = "점검한 사용자 id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    @Schema(description = "점검 여부", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean isChecked;

    @Schema(description = "점검 시각", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime checkedAt;

    public static ChecklistResultResponseDto fromEntity(ChecklistResult result) {
        return ChecklistResultResponseDto.builder()
                .resultId(result.getResultId())
                .itemId(result.getChecklistItem().getItemId())
                .userId(result.getMember().getUserId())
                .isChecked(result.getIsChecked())
                .checkedAt(result.getCheckedAt())
                .build();
    }
}
