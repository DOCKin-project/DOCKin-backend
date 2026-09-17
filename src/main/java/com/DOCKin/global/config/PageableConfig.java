package com.DOCKin.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.data.web.SortArgumentResolver;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * {@code Pageable} 파라미터 해석 규칙 — <b>정렬은 서버가 정하고, 페이지 크기에는 상한이 있다.</b>
 *
 * <h3>왜 — {@code ?sort=없는컬럼}이 500이었다 (2026-09-17)</h3>
 * 스프링 데이터의 기본 리졸버는 요청의 {@code sort} 파라미터를 그대로 {@code Pageable}에 싣는다.
 * 작업일지·채팅은 서비스가 그 sort를 떼고 쿼리에 박힌 순서를 쓰지만({@code WorkLogsService.sizeOnly},
 * {@code ChatService.getChatHistory}), 안전교육·휴가 목록은 컨트롤러의 {@code Pageable}을 리포지토리에
 * 그대로 넘겼다. 거기에 {@code ?sort=foo}를 주면 파생 쿼리는 {@code PropertyReferenceException},
 * {@code @Query}는 하이버네이트 {@code SemanticException}이 나고, 둘 다 {@code GlobalExceptionHandler}에
 * 핸들러가 없어 캐치올 500이 됐다. 클라이언트 입력 하나가 서버 오류로 기록되는 자리였다.
 *
 * <p>예외를 400으로 매핑하는 길은 택하지 않았다. {@code @Query} 쪽 예외는
 * {@code InvalidDataAccessApiUsageException}으로 감싸져 오는데, 그건 진짜 버그(잘못 쓴 JPQL)도 같은
 * 타입으로 오므로 400으로 돌리면 버그가 클라이언트 탓으로 숨는다.
 *
 * <h3>대신 — 요청의 {@code sort}를 아예 읽지 않는다</h3>
 * 이 저장소의 목록 API는 전부 순서가 제품 결정이다(최신순·커서). 클라이언트가 고를 수 있는 정렬이
 * 하나도 없고, {@code PageableSortDefaultTest}가 모든 {@code Pageable} 파라미터에
 * {@code @PageableDefault(sort = …)}가 있도록 강제한다. 그러니 <b>그 애노테이션이 곧 정렬이다</b>:
 * 여기 등록한 리졸버는 sort 파라미터 자리에 항상 {@link Sort#unsorted()}를 넣고, 스프링 데이터는
 * 그 경우 {@code @PageableDefault}의 sort로 채운다. 작업일지 컨트롤러 주석의 "애노테이션은 계약을 적어
 * 둔 것이지 동작을 만드는 것이 아니다"는 이 클래스 이후 "동작도 만든다"가 된다 — 서비스의
 * {@code sizeOnly}는 그대로 둔다. 커서가 있으면 page 번호를 버리는 일은 여전히 거기 몫이다.
 *
 * <p>스프링 데이터의 리졸버를 대체하지 않고 <b>앞에 하나 더 세운다.</b> 기본 리졸버는
 * {@code SpringDataWebConfiguration}이 {@code pageableResolver}라는 이름으로 고정해 만들고
 * 같은 이름 빈으로 갈아끼우려면 빈 오버라이딩을 켜야 한다. 대신 {@link WebMvcConfigurer}로 등록하고
 * {@link Ordered#HIGHEST_PRECEDENCE}를 주면 {@code DelegatingWebMvcConfiguration}이 설정자를 순서대로
 * 부르므로 이 리졸버가 목록 앞에 서고, 스프링은 같은 파라미터를 지원하는 첫 리졸버를 쓴다.
 * {@code PageableConfigTest}가 그 순서와 동작을 검사한다.
 *
 * <h3>페이지 크기 상한 — {@code spring.data.web.pageable.max-page-size}</h3>
 * 상한을 두지 않으면 스프링 데이터 기본값 2000까지 받는다. {@code Page} 응답은 COUNT까지 같이 돌고,
 * 작업일지 한 행은 이미지·댓글을 끌고 온다. 값은 스프링 부트의 키를 그대로 쓴다 — 기본 리졸버에도
 * 같은 값이 들어가므로 어느 쪽이 답하든 상한이 같다. 넘치는 {@code size}는 400이 아니라
 * <b>조용히 상한으로 깎인다</b>(스프링 데이터 동작). 클라이언트는 응답의 {@code size}를 보면 된다.
 */
@Configuration
public class PageableConfig implements WebMvcConfigurer, Ordered {

    @Value("${spring.data.web.pageable.max-page-size}")
    private int maxPageSize;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        ServerSortedPageableResolver resolver = new ServerSortedPageableResolver();
        resolver.setMaxPageSize(maxPageSize);
        resolvers.add(resolver);
    }

    /** 스프링 데이터 리졸버 그대로에 sort 자리만 {@link IgnoreRequestSort}. 이름이 있는 건 테스트가 목록에서 찾기 위해서다. */
    static final class ServerSortedPageableResolver extends PageableHandlerMethodArgumentResolver {
        ServerSortedPageableResolver() {
            super(new IgnoreRequestSort());
        }
    }

    /** 요청의 {@code sort}를 읽지 않는다. 비어 있으면 스프링 데이터가 {@code @PageableDefault}의 sort로 채운다. */
    static final class IgnoreRequestSort implements SortArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return Sort.class.equals(parameter.getParameterType());
        }

        @Override
        public Sort resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                    NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            return Sort.unsorted();
        }
    }
}
