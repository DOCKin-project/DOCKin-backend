package com.DOCKin.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 회원가입 응답. 사원번호 문자열을 {@code text/plain}으로 돌려주던 것을 JSON으로 바꿨다(P2-20-4) —
 * 다른 생성 API가 전부 201 + JSON이라 이 하나만 클라이언트가 다르게 파싱해야 했다.
 */
@Getter
@AllArgsConstructor
@Schema(description = "회원가입 res dto")
public class SignupResponseDto {
    @Schema(description = "가입된 사원번호", example = "E2026001", requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;
}
