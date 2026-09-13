package com.DOCKin.global.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.stereotype.Controller;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-15-3 — <b>페이징 엔드포인트는 정렬을 갖고 있어야 한다.</b>
 *
 * <h3>왜 컨트롤러 하나가 아니라 전부를 훑는가</h3>
 * P2-15-3은 {@code GET /api/work-logs}에서 나왔다.
 * <pre>
 *   &#64;PageableDefault(size = 20, direction = Sort.Direction.DESC)   // sort가 없다
 * </pre>
 * {@code direction}은 정렬 <b>속성이 있을 때</b> 그 방향을 정하는 값이다. 속성이 비어 있으면
 * 방향만으로는 정렬이 만들어지지 않아 이 Pageable은 <b>unsorted</b>가 된다.
 * 그런데 <b>애노테이션은 그럴듯하게 붙어 있다</b> — 읽는 사람은 정렬이 걸린 줄로 본다.
 *
 * <p>정렬 없는 OFFSET 페이징은 페이지 경계에서 행이 <b>중복되거나 누락된다.</b>
 * 그리고 이건 예외를 내지 않는다. 목록이 나오긴 나오므로 <b>틀린 곳이 로그에 남지 않는다</b> —
 * {@code @Lob}이 {@code validate}를 통과하던 것, {@code Upgrade} 헤더만 조용히 걷어지던 것과
 * 같은 자리다.
 *
 * <p>그래서 한 곳을 고치는 것으로는 부족하다. <b>같은 실수가 다음 엔드포인트에서 다시
 * 나오지 않게</b> 컨트롤러 전체를 훑는다. 새 페이징 API를 추가하면서 {@code sort}를
 * 빠뜨리면 이 테스트가 그 자리에서 실패한다.
 *
 * <h3>애플리케이션 컨텍스트를 띄우지 않는다</h3>
 * {@code ClassPathScanningCandidateComponentProvider}로 바이트코드만 훑으므로 DB도 컨테이너도
 * 필요 없다. 검증 대상이 <b>런타임 동작이 아니라 선언</b>이라 그것으로 충분하고, 대신 이
 * 테스트는 CI에서 몇 밀리초에 끝난다.
 *
 * <h3>이미 알고 있는 위반은 목록으로 둔다</h3>
 * {@link #KNOWN_UNSORTED}에 남은 것은 <b>고치지 못해서가 아니라 정렬 키가 제품 결정이기
 * 때문</b>이다(아래). 목록을 두되 <b>양방향으로</b> 단언한다 — 새 위반이 생겨도 실패하고,
 * 목록에 적힌 것이 고쳐졌는데 목록이 남아 있어도 실패한다. 한쪽만 보면 이 목록은
 * <b>조용히 낡는다.</b>
 */
class PageableSortDefaultTest {

    private static final String BASE_PACKAGE = "com.DOCKin";

    /**
     * 정렬이 없다는 것을 <b>알고 둔</b> 엔드포인트.
     *
     * <ul>
     *   <li>{@code ChatRoomController#findAllRooms} — 채팅방 목록의 정렬 키는
     *       {@code last_message_at}이 자연스럽지만, 그 컬럼은 P2-12-4가 <b>경합으로 실제
     *       마지막 메시지가 아닐 수 있다</b>고 지적한 자리다. 순서의 기준으로 삼기 전에
     *       그쪽을 먼저 정해야 하므로 작업일지 수정에 섞지 않았다. → P2-12-8</li>
     * </ul>
     */
    private static final Set<String> KNOWN_UNSORTED = new LinkedHashSet<>(Set.of(
            "ChatRoomController#findAllRooms"
    ));

    @Test
    @DisplayName("Pageable을 받는 모든 컨트롤러 메서드에 기본 정렬이 있다")
    void 페이징_엔드포인트에_기본_정렬이_있다() {
        Set<String> unsorted = new TreeSet<>(Comparator.naturalOrder());

        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (!Pageable.class.isAssignableFrom(parameter.getType())) {
                        continue;
                    }
                    if (!hasDefaultSort(parameter)) {
                        unsorted.add(controller.getSimpleName() + "#" + method.getName());
                    }
                }
            }
        }

        print(unsorted);

        assertEquals(new TreeSet<>(KNOWN_UNSORTED), unsorted, """

                기본 정렬이 없는 페이징 엔드포인트 목록이 KNOWN_UNSORTED와 다르다.

                  늘었다면 -- @PageableDefault에 sort를 넣어라. direction만으로는 정렬이
                  만들어지지 않고, 정렬 없는 OFFSET 페이징은 페이지 경계에서 행을
                  중복시키거나 누락시킨다. 정렬 키가 유니크하지 않으면(created_at 등)
                  PK를 뒤에 붙여 전순서로 만들어야 한다.

                  줄었다면 -- 고친 것이므로 KNOWN_UNSORTED에서 그 항목을 지워라.
                  목록을 그대로 두면 다시 깨졌을 때 이 테스트가 알려주지 못한다.
                """);
    }

    /**
     * {@code @PageableDefault(sort = ...)} 또는 {@code @SortDefault} 중 하나로 정렬이 선언됐는가.
     *
     * <p>둘 다 보는 이유는 Spring Data가 두 가지를 모두 허용하기 때문이다. 한쪽만 검사하면
     * <b>정상인 코드를 위반으로 잡아</b> 이 테스트를 끄고 싶게 만든다.
     */
    private boolean hasDefaultSort(Parameter parameter) {
        PageableDefault pageableDefault = parameter.getAnnotation(PageableDefault.class);
        if (pageableDefault != null && pageableDefault.sort().length > 0) {
            return true;
        }
        SortDefault sortDefault = parameter.getAnnotation(SortDefault.class);
        return sortDefault != null && sortDefault.sort().length > 0;
    }

    /**
     * {@code @Controller}가 붙은 클래스를 모은다.
     *
     * <p>{@code @RestController}는 {@code @Controller}를 메타 애노테이션으로 갖고 있어
     * 이 필터 하나에 함께 걸린다.
     */
    private Set<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        Set<Class<?>> found = new LinkedHashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                found.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "스캔된 컨트롤러를 로드하지 못했다: " + definition.getBeanClassName(), e);
            }
        }

        // 스캐너가 아무것도 못 찾으면 위 반복이 통째로 비어 테스트가 조용히 통과한다.
        // 패키지명이 바뀌거나 스캔이 깨졌을 때 그것을 여기서 잡는다.
        if (found.isEmpty()) {
            throw new IllegalStateException(
                    BASE_PACKAGE + " 아래에서 컨트롤러를 하나도 찾지 못했다 - 스캔이 깨졌다");
        }
        return found;
    }

    private void print(Set<String> unsorted) {
        System.out.println();
        System.out.println("=== 기본 정렬이 없는 페이징 엔드포인트 ===");
        if (unsorted.isEmpty()) {
            System.out.println("  (없음)");
        } else {
            unsorted.forEach(name -> System.out.println("  " + name
                    + (KNOWN_UNSORTED.contains(name) ? "   <- 알고 둔 것" : "   <- 새로 생긴 것")));
        }
        System.out.println();
    }
}
