package com.DOCKin.safetyCourse.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "근로자 교육 이수 상태 업데이트 요청")
public class SafetyWatchStatusRequestDto {

    /**
     * {@code @NotBlank}였다. {@code CharSequence} 전용이라 {@code Integer}에는 검증기가 없고,
     * {@code @Valid}가 붙은 컨트롤러는 요청마다 {@code UnexpectedTypeException}(HV000030)을 맞았다.
     * 캐치올에 걸려 <b>이 엔드포인트는 호출될 때마다 500</b>이었다.
     *
     * <p>필드가 하나뿐이라 "필수"의 의미는 <b>null이 아닐 것</b>이고, 그것이 {@code @NotNull}이다.
     * 같은 함정을 {@code BeanValidationConstraintTest}가 막는다.
     */
    @Schema(description = "교육 자료 id", example = "101", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "교육 번호는 필수입니다.")
    private Integer courseId;

}