package com.DOCKin.worklog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 반려 본문. 사유 없는 반려는 작성자가 무엇을 고쳐야 할지 모르므로 필수다. */
public record WorkLogRejectRequestDto(
        @Schema(description = "반려 사유 (필수)", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 500) String comment) {}
