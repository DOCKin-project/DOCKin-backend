package com.DOCKin.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * "여기까지 읽었다" (ADR-0008 11-1). 시각이 아니라 {@code roomSeq}다.
 *
 * <p>앱은 화면에 보인 가장 큰 {@code roomSeq}를 보낸다 — 메시지마다가 아니라 방을 벗어날 때나
 * 몇 초 디바운스로. 서버는 {@code GREATEST}로 올리기만 하므로 늦게 도착한 작은 값이 큰 값을
 * 되돌리지 않고, 같은 값이 두 번 와도 같다.
 */
@Schema(description = "읽음 처리 req dto")
public record ChatReadRequestDto(
        @Schema(description = "읽은 마지막 roomSeq", example = "47", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @PositiveOrZero Long upToSeq) {
}
