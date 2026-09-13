package com.DOCKin.checklist.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "체크리스트 항목 생성 req dto")
public class ChecklistItemRequestDto {

    @Schema(description = "항목 내용", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "항목 내용은 필수입니다.")
    private String content;

    @Schema(description = "출력 순서", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "순서는 필수입니다.")
    @Positive(message = "순서는 1 이상이어야 합니다.")
    private Integer sequence;
}
