package com.DOCKin.worklog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 승인 본문. 코멘트는 선택이라 본문 자체가 없어도 된다. */
public record WorkLogApproveRequestDto(
        @Schema(description = "승인 코멘트 (선택)")
        @Size(max = 500) String comment) {}
