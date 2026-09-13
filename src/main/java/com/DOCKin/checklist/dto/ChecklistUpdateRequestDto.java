package com.DOCKin.checklist.dto;

import com.DOCKin.checklist.model.ChecklistPhase;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "체크리스트 수정 req dto (null인 필드는 변경하지 않음)")
public class ChecklistUpdateRequestDto {

    @Schema(description = "체크리스트 제목")
    private String title;

    @Schema(description = "점검 단계 (PRE/POST)")
    private ChecklistPhase phase;
}
