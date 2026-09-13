package com.DOCKin.safetyCourse.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

/**
 * {@code @NotBlank}가 {@code Integer}에 붙어 있으면 무슨 일이 일어나는가.
 *
 * <p>{@link SafetyWatchStatusRequestDto#courseId}는 {@code Integer}인데 {@code @NotBlank}가 붙어 있고,
 * 컨트롤러는 {@code @Valid}로 받는다. {@code @NotBlank}는 {@code CharSequence} 전용이므로
 * <b>검증기를 찾지 못한다.</b> 그때 무엇이 되는지를 확인한다 — 조용히 통과인지, 예외인지.
 *
 * <p>답에 따라 대응이 달라진다. 통과라면 검증이 없는 것이고, 예외라면
 * <b>이 엔드포인트는 호출될 때마다 500</b>이다. 후자면 이슈 #31과 같은 부류다.
 *
 * <p>DB도 컨텍스트도 필요 없다 — 검증기만 있으면 되므로 CI에서 실제로 돈다.
 * 이 저장소 테스트 대부분이 CI에서 skip되는 것과 다르다.
 */
class SafetyWatchStatusValidationTest {

    @Test
    @DisplayName("@NotBlank가 Integer 필드에 붙었을 때의 실제 동작")
    void notBlank가_Integer에_붙으면() throws Exception {
        Field courseId = SafetyWatchStatusRequestDto.class.getDeclaredField("courseId");

        System.out.println();
        System.out.println("=== @NotBlank on Integer — 실제 동작 확인 ===");
        System.out.println("필드 타입      : " + courseId.getType().getName());
        System.out.println("붙어 있는 제약 : " + java.util.Arrays.toString(courseId.getAnnotations()));
        System.out.println();

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            SafetyWatchStatusRequestDto dto = new SafetyWatchStatusRequestDto();

            try {
                var violations = validator.validate(dto);
                System.out.println("결과: 예외 없이 통과했다.");
                System.out.println("위반 건수: " + violations.size());
                violations.forEach(v -> System.out.println("  - " + v.getPropertyPath() + ": " + v.getMessage()));
                System.out.println();
                System.out.println("→ 검증이 걸리지 않는다. courseId가 null이어도 서비스까지 내려간다.");
            } catch (RuntimeException e) {
                System.out.println("결과: 예외가 났다.");
                System.out.println("  예외 : " + e.getClass().getName());
                System.out.println("  메시지: " + e.getMessage());
                System.out.println();
                System.out.println("→ @Valid가 붙은 컨트롤러 경로는 요청마다 이 예외를 맞는다.");
                System.out.println("  GlobalExceptionHandler의 캐치올에 걸리면 500이 된다.");
            }
        }
        System.out.println();
    }
}
