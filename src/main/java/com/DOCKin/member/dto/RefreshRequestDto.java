package com.DOCKin.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@Builder
@NoArgsConstructor
@Schema(description = "토큰 갱신 req dto")
public class RefreshRequestDto {
    @Schema(description = "로그인 때 받은 리프레시 토큰", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "리프레시 토큰은 필수입니다.")
    private String refreshToken;
}
