package com.DOCKin.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 모든 요청에 추적 ID를 붙여 MDC에 넣는다(백로그 P2-11-3).
 *
 * <p>규칙과 값의 출처는 {@link TraceId}에 적었다. 이 클래스는 그 값을 <b>요청 하나의 생애에
 * 정확히 맞춰 넣고 빼는 것</b>만 담당한다.
 *
 * <h3>가장 높은 우선순위인 이유</h3>
 * 인증 실패도 예외도 로그를 남긴다. 필터가 뒤에 있으면 <b>그 로그들만 추적 ID 없이 나가고,</b>
 * 하필 그것들이 추적이 가장 필요한 줄이다.
 *
 * <h3>{@code finally}가 이 클래스의 핵심이다</h3>
 * 톰캣은 스레드를 재사용한다. 지우지 않으면 <b>다음 요청이 앞 요청의 추적 ID를 달고 로그를 남긴다</b> --
 * 없느니만 못한 상태다. 무관한 두 요청이 하나로 보이기 때문이다.
 * 그리고 이 결함은 부하가 있어야 드러나므로 개발 중에는 거의 보이지 않는다.
 *
 * <h3>알아둘 한계 -- 리액티브 경로에서는 끊긴다</h3>
 * MDC는 {@code ThreadLocal}이다. {@code /api/ai/rt-translate}는 {@code Mono}를 반환하고
 * WebClient 호출은 Reactor 스레드에서 이어지므로, <b>그쪽에서 찍히는 로그에는 추적 ID가 없다.</b>
 * 서블릿 스레드에서 도는 구간까지만 이어진다는 뜻이다.
 * 리액터 컨텍스트로 전파하는 것은 별도 작업이고, 지금은 "안 되는 줄 알고 안 하는 것"으로 남긴다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(TraceId.HEADER);
        String traceId = TraceId.isSafe(header) ? header : TraceId.generate();

        MDC.put(TraceId.MDC_KEY, traceId);

        // 클라이언트가 자기 로그·오류 보고에 이 값을 적을 수 있게 되돌려준다.
        // 검사를 통과한 값만 여기 오므로 헤더 인젝션이 되지 않는다.
        response.setHeader(TraceId.HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceId.MDC_KEY);
        }
    }
}
