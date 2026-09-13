package com.DOCKin.checklist.dto;

import com.DOCKin.checklist.model.ChecklistItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "체크리스트 항목 res dto (관리자용, 점검 상태 없음)")
public class ChecklistItemSimpleResponseDto {

    @Schema(description = "항목 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer itemId;

    @Schema(description = "항목 내용", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    @Schema(description = "출력 순서", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer sequence;

    public static ChecklistItemSimpleResponseDto fromEntity(ChecklistItem item) {
        return ChecklistItemSimpleResponseDto.builder()
                .itemId(item.getItemId())
                .content(item.getContent())
                .sequence(item.getSequence())
                .build();
    }
}
