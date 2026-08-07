package com.DOCKin.global.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 클라이언트 오류가 500으로 승격되지 않는지 검증한다.
 *
 * <h3>왜 필요한가</h3>
 * {@link GlobalExceptionHandler}의 맨 아래 {@code Exception} 캐치올은 <b>명시적으로 잡지 않은
 * 모든 예외를 500으로 만든다.</b> 핸들러를 하나 지우면 그 예외는 조용히 서버 오류가 되고,
 * 컴파일도 통과하고 다른 테스트도 통과한다. 실제로 두 번 그렇게 됐다 --
 * {@code AccessDeniedException}이 403 대신 500이었고,
 * {@code NoResourceFoundException}이 404 대신 500이었다(이슈 #31).
 *
 * <p>여기서 핸들러를 지우면 <b>이 테스트가 컴파일되지 않는다.</b> 조용히 넘어가지 않는다는 뜻이고,
 * 그것이 이 테스트가 노리는 것이다.
 *
 * <h3>왜 MockMvc가 아니라 핸들러를 직접 호출하는가</h3>
 * 원래 이유는 <b>CI에 DB가 없다</b>는 것이었다. {@code @WebMvcTest}는 스프링 컨텍스트를 띄우고
 * 이 저장소의 보안 설정은 그 과정에서 {@code UserDetailsService}를 거쳐 DB에 닿으므로,
 * 회귀를 잡으려고 만든 테스트가 정작 CI에서 통째로 skip될 판이었다.
 *
 * <p><b>그 제약은 사라졌다</b>(P2-11-5, Testcontainers). 지금 이 방식을 유지하는 것은
 * 이제 필요가 아니라 선택이다 -- 컨텍스트 없이 도는 이 테스트는 1초 안에 끝나고,
 * 검증 대상인 "예외 -> 상태 코드 매핑"에는 컨텍스트가 필요하지 않다.
 *
 * <p>대신 <b>"스프링이 이 예외를 이 핸들러로 보내는가"는 여전히 검증하지 못한다.</b>
 * 그것은 프레임워크의 계약이고, 실제 동작은 컨테이너를 띄워 확인했다.
 * 이 부분이 불안해지면 {@code @WebMvcTest}로 옮기는 선택지가 이제는 열려 있다.
 *
 * <h3>무엇을 단언하는가</h3>
 * {@link ErrorResponseDto}는 {@code status / message / timestamp} 세 필드이고 프론트엔드와의
 * 계약이다. 메시지 문구는 바뀔 수 있으므로 고정하지 않고, <b>HTTP 상태 코드와 본문의
 * status 필드가 서로 맞는지</b>를 본다. 그 둘이 어긋나면 클라이언트가 분기를 잘못 탄다.
 */
@DisplayName("전역 예외 처리 - 클라이언트 오류가 500으로 새지 않는다")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("이슈 #31 - 매핑 없는 경로는 404다. 500이면 로드밸런서가 멀쩡한 서버를 죽은 것으로 본다")
    void noResourceFoundReturns404() {
        // 인자는 (메서드, 요청 경로, 리소스 경로)다. GET / 가 실제로 500을 내던 그 상황이다.
        var response = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/", "/"));

        assertStatus(response, 404);
    }

    @Test
    @DisplayName("필수 파라미터 누락은 400이다")
    void missingParameterReturns400() {
        var response = handler.handleMissingParameter(
                new MissingServletRequestParameterException("name", "String"));

        assertStatus(response, 400);
    }

    @Test
    @DisplayName("파라미터 이름이 응답에 담긴다 - 무엇이 빠졌는지 알려주지 않으면 400의 값어치가 없다")
    void missingParameterTellsWhichOne() {
        var response = handler.handleMissingParameter(
                new MissingServletRequestParameterException("employeeNo", "String"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).contains("employeeNo");
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type은 415다")
    void unsupportedMediaTypeReturns415() {
        var response = handler.handleUnsupportedMediaType(
                new HttpMediaTypeNotSupportedException("지원하지 않는 형식"));

        assertStatus(response, 415);
    }

    @Test
    @DisplayName("허용되지 않은 메서드는 405다")
    void methodNotSupportedReturns405() {
        var response = handler.handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("POST"));

        assertStatus(response, 405);
    }

    @Test
    @DisplayName("읽을 수 없는 본문은 400이다")
    void notReadableReturns400() {
        var response = handler.handleNotReadable(
                new HttpMessageNotReadableException("깨진 JSON", (org.springframework.http.HttpInputMessage) null));

        assertStatus(response, 400);
    }

    @Test
    @DisplayName("권한 거부는 403이다 - 캐치올이 삼키면 500이 된다")
    void accessDeniedReturns403() {
        var response = handler.handleAccessDenied(new AccessDeniedException("거부"));

        assertStatus(response, 403);
    }

    @Test
    @DisplayName("예상하지 못한 예외만 500이다 - 캐치올이 있어야 하는 이유이기도 하다")
    void unexpectedExceptionReturns500() {
        var response = handler.handleUnexpected(new IllegalStateException("예상 못 한 것"));

        assertStatus(response, 500);
    }

    @Test
    @DisplayName("응답 본문에 예외 메시지가 새지 않는다 - 내부 구현 정보가 들어 있다")
    void unexpectedExceptionHidesItsMessage() {
        var response = handler.handleUnexpected(
                new IllegalStateException("com.DOCKin.internal.SecretDetail에서 실패"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.getMessage())
                .doesNotContain("SecretDetail");
    }

    /**
     * HTTP 상태 코드와 본문의 status 필드를 함께 본다.
     * 둘이 어긋나면 클라이언트가 어느 쪽을 믿느냐에 따라 다르게 동작한다.
     */
    private void assertStatus(ResponseEntity<ErrorResponseDto> response, int expected) {
        assertThat(response.getStatusCode().value()).isEqualTo(expected);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(expected);
    }
}
