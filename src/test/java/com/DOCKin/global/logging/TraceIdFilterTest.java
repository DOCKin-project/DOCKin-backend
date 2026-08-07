package com.DOCKin.global.logging;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추적 ID가 붙고, 이어지고, <b>다음 요청으로 새지 않는지</b> 검증한다.
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * 이 필터가 잘못 동작해도 <b>애플리케이션은 아무 증상이 없다.</b> 요청은 정상 처리되고
 * 로그도 남는다. 다만 그 로그의 추적 ID가 틀릴 뿐이다. 즉 <b>틀렸다는 사실이
 * 틀린 곳에 남지 않는</b> 종류이고, 이 저장소가 반복해서 잡아온 부류다.
 *
 * <p>특히 MDC 누수({@link #mdcDoesNotLeakToTheNextRequest})는 <b>부하가 있어야 드러난다.</b>
 * 톰캣이 스레드를 재사용해야 앞 요청의 값이 보이기 때문에, 개발 중 한 번에 한 요청씩
 * 눌러보는 상황에서는 영원히 정상으로 보인다. 추적 ID가 아예 없는 것보다 나쁜 상태다 --
 * 무관한 두 요청이 하나로 읽힌다.
 */
@DisplayName("추적 ID 필터 - 붙고, 이어지고, 새지 않는다")
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("헤더가 없으면 서버가 만든다 - 추적 ID 없는 요청이 있어서는 안 된다")
    void generatesWhenHeaderAbsent() throws Exception {
        var response = new MockHttpServletResponse();

        String seen = captureTraceIdDuringChain(new MockHttpServletRequest(), response);

        assertThat(seen).isNotBlank();
        assertThat(response.getHeader(TraceId.HEADER)).isEqualTo(seen);
    }

    @Test
    @DisplayName("헤더가 있으면 그 값을 잇는다 - 프론트엔드가 이미 만든 ID가 있으면 새로 만들 이유가 없다")
    void reusesIncomingHeader() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(TraceId.HEADER, "front-abc-123");

        String seen = captureTraceIdDuringChain(request, new MockHttpServletResponse());

        assertThat(seen).isEqualTo("front-abc-123");
    }

    @Test
    @DisplayName("줄바꿈이 든 헤더는 버린다 - 그대로 넣으면 로그에 가짜 줄을 만들 수 있다")
    void rejectsHeaderWithControlCharacters() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(TraceId.HEADER, "abc\nINFO 관리자 권한 승격됨");

        String seen = captureTraceIdDuringChain(request, new MockHttpServletResponse());

        assertThat(seen).doesNotContain("\n").doesNotContain("승격");
    }

    @Test
    @DisplayName("지나치게 긴 헤더는 버린다 - 로그 한 줄을 추적 ID가 잡아먹는다")
    void rejectsOverlongHeader() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(TraceId.HEADER, "a".repeat(200));

        String seen = captureTraceIdDuringChain(request, new MockHttpServletResponse());

        assertThat(seen).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("요청이 끝나면 MDC가 비워진다 - 안 지우면 다음 요청이 앞 요청의 ID를 달고 로그를 남긴다")
    void mdcDoesNotLeakToTheNextRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(MDC.get(TraceId.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("체인이 예외를 던져도 MDC가 비워진다 - 실패한 요청일수록 다음 요청을 오염시키면 안 된다")
    void mdcIsClearedEvenWhenChainThrows() {
        FilterChain exploding = (req, res) -> {
            throw new IllegalStateException("처리 중 실패");
        };

        try {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), exploding);
        } catch (Exception expected) {
            // 예외 자체는 이 테스트의 관심사가 아니다. 관심사는 그 뒤의 MDC 상태다.
        }

        assertThat(MDC.get(TraceId.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("본문 traceId가 헤더를 이긴다 - chat_history에 저장되는 값과 로그가 같아야 한다")
    void bodyTraceIdOverridesTheFilterValue() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(TraceId.HEADER, "from-header");

        String seen = captureTraceIdDuringChain(request, new MockHttpServletResponse(),
                () -> TraceId.override("from-body"));

        assertThat(seen).isEqualTo("from-body");
    }

    @Test
    @DisplayName("본문 traceId가 안전하지 않으면 필터 값이 남는다 - 추적 ID가 비는 일은 없다")
    void unsafeOverrideKeepsTheFilterValue() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(TraceId.HEADER, "from-header");

        String seen = captureTraceIdDuringChain(request, new MockHttpServletResponse(),
                () -> TraceId.override("깨진 값\n두 번째 줄"));

        assertThat(seen).isEqualTo("from-header");
    }

    /**
     * 체인 안에서 본 MDC 값을 돌려준다.
     * 필터가 {@code finally}에서 지우므로 <b>체인이 도는 동안에만</b> 관측할 수 있다.
     */
    private String captureTraceIdDuringChain(MockHttpServletRequest request,
                                             MockHttpServletResponse response) throws Exception {
        return captureTraceIdDuringChain(request, response, () -> {
        });
    }

    private String captureTraceIdDuringChain(MockHttpServletRequest request,
                                             MockHttpServletResponse response,
                                             Runnable insideChain) throws Exception {
        String[] captured = new String[1];
        filter.doFilter(request, response, (req, res) -> {
            insideChain.run();
            captured[0] = MDC.get(TraceId.MDC_KEY);
        });
        return captured[0];
    }
}
