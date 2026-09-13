package com.DOCKin.global.validation;

import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bean Validation 제약이 <b>필드 타입과 맞는지</b> 전수 검사한다.
 *
 * <h3>왜 필요한가 — 실제로 당했다</h3>
 * {@code SafetyWatchStatusRequestDto.courseId}가 {@code Integer}인데 {@code @NotBlank}가 붙어
 * 있었다. {@code @NotBlank}는 {@code CharSequence} 전용이라 검증기를 찾지 못하고,
 * Hibernate Validator는 그때 <b>조용히 통과시키는 대신 예외를 던진다</b>
 * ({@code UnexpectedTypeException}, HV000030). {@code @Valid}가 붙은 컨트롤러였으므로
 * <b>그 엔드포인트는 호출될 때마다 500</b>이었다.
 *
 * <p>가장 나쁜 점은 <b>컴파일이 통과한다</b>는 것이다. 애노테이션은 아무 타입에나 붙고,
 * 불일치는 첫 요청이 들어와야 드러난다. 그 엔드포인트를 아무도 호출하지 않으면 영영 모른다.
 *
 * <h3>왜 정적 검사인가</h3>
 * DTO를 실제로 만들어 {@code validator.validate()}를 돌리면 확실하지만, 생성자와 필수 필드가
 * 제각각이라 <b>인스턴스를 만드는 일 자체가 더 깨지기 쉽다.</b> 제약↔타입 규칙은 명세에 고정되어
 * 있으므로 표로 두고 리플렉션으로 대조하는 편이 안정적이다.
 *
 * <p><b>DB도 컨텍스트도 필요 없다 — CI에서 실제로 돈다.</b> 이 저장소의 DB 테스트 대부분이
 * CI에서 skip되는 것과 다르다.
 */
class BeanValidationConstraintTest {

    private static final String BASE_PACKAGE = "com.DOCKin";

    // 아래 ALLOWED보다 먼저 선언해야 한다 — 정적 초기화는 선언 순서를 따르므로,
    // 뒤에 두면 ALLOWED를 채우는 시점에 이 배열들이 아직 null이다.
    private static final Class<?>[] NUMERIC = {
            Number.class, byte.class, short.class, int.class, long.class, float.class, double.class};
    private static final Class<?>[] NUMERIC_OR_TEXT = {
            Number.class, CharSequence.class,
            byte.class, short.class, int.class, long.class, float.class, double.class};
    private static final Class<?>[] TEMPORAL = {
            TemporalAccessor.class, Date.class, Calendar.class};

    /**
     * 제약 애노테이션이 허용하는 타입. Jakarta Bean Validation 3.x 명세의 표를 옮긴 것이다.
     *
     * <p>{@code null}은 어떤 제약에서도 유효하므로 래퍼 타입 여부는 따지지 않는다.
     * 원시 타입은 {@code Number}에 대입되지 않으므로 별도로 처리한다.
     */
    private static final Map<Class<? extends Annotation>, Class<?>[]> ALLOWED = new LinkedHashMap<>() {{
        put(NotBlank.class, new Class<?>[]{CharSequence.class});
        put(Email.class, new Class<?>[]{CharSequence.class});
        put(Pattern.class, new Class<?>[]{CharSequence.class});
        put(NotEmpty.class, new Class<?>[]{CharSequence.class, Collection.class, Map.class});
        put(Size.class, new Class<?>[]{CharSequence.class, Collection.class, Map.class});
        put(Positive.class, NUMERIC);
        put(PositiveOrZero.class, NUMERIC);
        put(Negative.class, NUMERIC);
        put(NegativeOrZero.class, NUMERIC);
        put(Min.class, NUMERIC);
        put(Max.class, NUMERIC);
        put(DecimalMin.class, NUMERIC_OR_TEXT);
        put(DecimalMax.class, NUMERIC_OR_TEXT);
        put(Past.class, TEMPORAL);
        put(PastOrPresent.class, TEMPORAL);
        put(Future.class, TEMPORAL);
        put(FutureOrPresent.class, TEMPORAL);
        put(AssertTrue.class, new Class<?>[]{Boolean.class, boolean.class});
        put(AssertFalse.class, new Class<?>[]{Boolean.class, boolean.class});
    }};

    @Test
    @DisplayName("모든 DTO의 제약 애노테이션이 필드 타입과 맞는가")
    void 제약과_타입이_어긋난_필드가_없어야_한다() {
        List<Class<?>> classes = scan();
        assertThat(classes)
                .as("스캔이 아무것도 못 찾았다면 검사가 아니라 통과 흉내를 내는 것이다")
                .isNotEmpty();

        List<String> broken = new ArrayList<>();
        int checked = 0;

        for (Class<?> type : classes) {
            for (Field field : type.getDeclaredFields()) {
                for (Map.Entry<Class<? extends Annotation>, Class<?>[]> rule : ALLOWED.entrySet()) {
                    if (!field.isAnnotationPresent(rule.getKey())) continue;
                    checked++;
                    if (!accepts(rule.getValue(), field.getType())) {
                        broken.add(String.format("%s.%s : @%s 는 %s 에 쓸 수 없다 (필드 타입 %s)",
                                type.getSimpleName(), field.getName(),
                                rule.getKey().getSimpleName(), describe(rule.getValue()),
                                field.getType().getSimpleName()));
                    }
                }
                collectBadPattern(type, field, broken);
            }
        }

        System.out.printf("%n검사한 클래스 %d개 / 제약 %d개%n", classes.size(), checked);
        assertThat(broken)
                .as("제약과 타입이 어긋나면 컴파일은 통과하고 첫 요청에서 500이 된다 "
                        + "(UnexpectedTypeException, HV000030)")
                .isEmpty();
    }

    /** {@code @Pattern}의 정규식이 컴파일되는지도 본다 — 이것 역시 런타임에야 터진다. */
    private void collectBadPattern(Class<?> type, Field field, List<String> broken) {
        Pattern p = field.getAnnotation(Pattern.class);
        if (p == null) return;
        try {
            java.util.regex.Pattern.compile(p.regexp());
        } catch (PatternSyntaxException e) {
            broken.add(String.format("%s.%s : @Pattern 정규식이 컴파일되지 않는다 — %s",
                    type.getSimpleName(), field.getName(), e.getDescription()));
        }
    }

    private boolean accepts(Class<?>[] allowed, Class<?> fieldType) {
        for (Class<?> a : allowed) {
            if (a.isAssignableFrom(fieldType) || a.equals(fieldType)) return true;
        }
        return fieldType.isArray()
                && (contains(allowed, Collection.class) || contains(allowed, Map.class));
    }

    private boolean contains(Class<?>[] arr, Class<?> c) {
        for (Class<?> x : arr) {
            if (x.equals(c)) return true;
        }
        return false;
    }

    private String describe(Class<?>[] allowed) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c : allowed) {
            if (sb.length() > 0) sb.append('/');
            sb.append(c.getSimpleName());
        }
        return sb.toString();
    }

    /**
     * {@code com.DOCKin} 아래의 모든 클래스를 훑는다.
     *
     * <p>DTO만 보지 않는 것은 의도다 — 엔티티나 설정 프로퍼티 클래스에 제약이 붙기도 하고,
     * <b>이름 규칙으로 거르면 규칙을 안 지킨 클래스가 그대로 빠져나간다.</b>
     */
    private List<Class<?>> scan() {
        var provider = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                return true;   // 인터페이스·추상 클래스도 필드를 가질 수 있다
            }
        };
        provider.addIncludeFilter(new RegexPatternTypeFilter(java.util.regex.Pattern.compile(".*")));

        List<Class<?>> out = new ArrayList<>();
        for (var bd : provider.findCandidateComponents(BASE_PACKAGE)) {
            try {
                out.add(Class.forName(bd.getBeanClassName()));
            } catch (Throwable ignored) {
                // 로딩할 수 없는 클래스는 제약을 걸 일도 없다
            }
        }
        return out;
    }
}
