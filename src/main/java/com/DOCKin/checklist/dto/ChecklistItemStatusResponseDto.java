package com.DOCKin.checklist.dto;

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
@Schema(description = "체크리스트 항목 res dto (사용자용, 점검 상태 포함)")
public class ChecklistItemStatusResponseDto {

    @Schema(description = "항목 번호", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer itemId;

    @Schema(description = "항목 내용", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    @Schema(description = "출력 순서", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer sequence;

    @Schema(description = "현재 체크 여부 (점검 기록이 없으면 false)", requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean checked;

    @Schema(description = "마지막 점검 시각 (기록 없으면 null)")
    private LocalDateTime lastCheckedAt;

    @Schema(description = "마지막으로 점검한 사용자 id (기록 없으면 null)")
    private String lastCheckedBy;
}
