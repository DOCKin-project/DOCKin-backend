package com.DOCKin.checklist.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "체크리스트 항목 수정 req dto (null인 필드는 변경하지 않음)")
public class ChecklistItemUpdateRequestDto {

    @Schema(description = "항목 내용")
    private String content;

    @Schema(description = "출력 순서", example = "1")
    @Positive(message = "순서는 1 이상이어야 합니다.")
    private Integer sequence;
}
