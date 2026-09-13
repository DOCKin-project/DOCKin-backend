package com.DOCKin.global.error;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * 전역 예외 처리.
 *
 * <h3>왜 늘렸는가</h3>
 * {@link BusinessException} 하나만 처리하고 있었다. 즉 <b>{@code @Valid} 검증 실패가 500</b>으로 나갔다.
 * 클라이언트 입장에서 "무엇을 잘못 보냈는지"와 "서버가 고장났는지"가 구분되지 않는 상태였다
 * (ADR-0005 4절, 백로그 P0-9).
 *
 * <h3>지킨 것 -- 응답 형태를 바꾸지 않는다</h3>
 * {@link ErrorResponseDto}는 {@code status / message / timestamp} 세 필드다.
 * {@link ErrorCode}에는 {@code C001} 같은 코드가 있지만 <b>응답에는 들어가지 않으며, 이 작업에서 추가하지 않았다.</b>
 * 필드를 늘리면 프론트엔드와의 계약이 바뀌고, 그건 예외 처리 보강과 별개의 결정이다. 코드는 로그에만 남는다.
 *
 * <h3>캐치올이 만드는 함정 -- 이 클래스의 핵심</h3>
 * 맨 아래 {@code Exception} 캐치올은 마지막 방어선이라 있어야 한다.
 * <b>문제는 그 그물이 너무 위쪽에 쳐져 있어서 클라이언트 오류까지 걷어 올린다는 것이다.</b>
 * 스프링은 더 구체적인 핸들러를 우선하므로, 명시적으로 잡아두지 않은 예외는 전부 500이 된다.
 *
 * <p>실제로 두 번 걸렸다.
 * <ul>
 *   <li>{@link AccessDeniedException} -- {@code RuntimeException}이라 <b>403이 500</b>으로 나갔다</li>
 *   <li>{@link NoResourceFoundException} -- 매핑 없는 경로가 <b>404가 아니라 500</b>으로 나갔다(이슈 #31).
 *       {@code GET /}가 500이었고, 로드밸런서가 그것을 헬스체크로 때리면 멀쩡한 서버가 죽은 것으로 판정된다</li>
 *   <li>{@link MaxUploadSizeExceededException} -- 용량 초과 업로드가 <b>413이 아니라 500</b>이었다(P2-9-4)</li>
 * </ul>
 *
 * <p><b>그래서 클라이언트 오류는 하나씩 명시적으로 잡는다.</b> 아래 핸들러들이 그 목록이고,
 * 지우면 조용히 500으로 돌아간다. 캐치올에 걸린다는 것은 <b>"우리가 예상하지 못했다"</b>는 뜻이어야 한다.
 *
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponseDto> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("BusinessException: {} - {}", errorCode.getCode(), errorCode.getMessage());
        return toResponse(errorCode, errorCode.getMessage());
    }

    /**
     * {@code @RequestBody}에 붙은 {@code @Valid} 실패. 이 핸들러가 없어서 500으로 나가던 대표 경로다.
     *
     * <p><b>필드 이름과 위반 사유만 보내고 입력값은 넣지 않는다.</b> 값을 그대로 되돌려주면
     * 비밀번호처럼 담으면 안 되는 입력이 응답과 로그에 남는다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining(", "));
        log.warn("검증 실패: {}", detail);
        return toResponse(ErrorCode.INVALID_INPUT_VALUE, message(ErrorCode.INVALID_INPUT_VALUE, detail));
    }

    /** {@code @RequestParam}/{@code @PathVariable}에 붙은 제약 위반({@code @Validated} 경로). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponseDto> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
        log.warn("제약 위반: {}", detail);
        return toResponse(ErrorCode.INVALID_INPUT_VALUE, message(ErrorCode.INVALID_INPUT_VALUE, detail));
    }

    /** 타입 불일치. {@code /logs/abc}처럼 숫자 자리에 문자가 온 경우다. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponseDto> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("타입 불일치: {}", e.getName());
        return toResponse(ErrorCode.INVALID_TYPE_VALUE,
                message(ErrorCode.INVALID_TYPE_VALUE, e.getName()));
    }

    /**
     * 본문을 읽지 못한 경우 -- 깨진 JSON, 빈 본문, 숫자 필드에 문자열 등.
     *
     * <p>예외 메시지를 그대로 노출하지 않는다. 파서 메시지에 <b>클래스명과 패키지 구조가 들어 있어</b>
     * 내부 구조가 새어 나간다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("요청 본문을 읽을 수 없음: {}", e.getMessage());
        return toResponse(ErrorCode.INVALID_INPUT_VALUE, "요청 본문의 형식이 올바르지 않습니다.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponseDto> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("허용되지 않은 메서드: {}", e.getMethod());
        return toResponse(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getMessage());
    }

    /** 필수 쿼리 파라미터 누락. {@code @RequestParam(required = true)}에 값이 안 온 경우다. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponseDto> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("필수 파라미터 누락: {}", e.getParameterName());
        return toResponse(ErrorCode.INVALID_INPUT_VALUE,
                message(ErrorCode.INVALID_INPUT_VALUE, e.getParameterName() + " 값이 필요합니다"));
    }

    /** {@code Content-Type}이 컨트롤러가 받는 형식과 다른 경우. 대부분 JSON 자리에 폼 데이터가 온 것이다. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponseDto> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        log.warn("지원하지 않는 Content-Type: {}", e.getContentType());
        return toResponse(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE.getMessage());
    }

    /**
     * 매핑된 것이 없는 경로. <b>이 핸들러가 없어서 {@code GET /}가 500으로 나가고 있었다</b>(이슈 #31).
     *
     * <p>없는 것과 고장난 것은 대응이 다르다 -- 전자는 요청을 고쳐야 하고 후자는 서버를 고쳐야 한다.
     * 그런데 아래 캐치올이 이 예외를 "처리되지 않은 예외"로 걷어 올려 <b>404여야 할 응답이 500</b>이 됐다.
     * 로드밸런서가 {@code /}를 헬스체크로 때리면 <b>멀쩡한 서버를 죽은 것으로 판정하는</b> 상태였다.
     *
     * <p><b>로그를 debug로 낮춘 이유</b> -- 여기 걸리는 것의 대부분은 오타 URL과 봇 스캔이다.
     * warn으로 두면 정상 트래픽에 경고가 끝없이 쌓이고, 그러면 사람이 경고를 안 보게 된다.
     * 서버가 할 일이 없는 종류의 오류다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleNoResourceFound(NoResourceFoundException e) {
        log.debug("매핑 없는 경로: {}", e.getResourcePath());
        return toResponse(ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.getMessage());
    }

    /**
     * 업로드 용량 초과. {@code spring.servlet.multipart.max-file-size}(10MB)를 넘은 경우다.
     *
     * <h4>이 핸들러가 생기려면 nginx를 먼저 고쳐야 했다</h4>
     * 이전에는 nginx {@code client_max_body_size}도 10M이라 <b>앱이 이 예외를 볼 기회가 거의 없었다.</b>
     * 멀티파트는 경계 문자열과 파트 헤더가 붙어 요청 전체가 파일보다 크므로,
     * 정확히 10MB인 파일은 nginx에서 먼저 413으로 잘렸고 그 응답은 nginx의 기본 HTML이었다.
     * <b>즉 한도를 정하는 주체가 앱이 아니라 우연히 앞에 선 프록시였다.</b>
     *
     * <p>nginx를 20M으로 올려 거절 주체를 앱으로 되돌렸다(P2-9-4). 이제 10MB 초과 업로드는
     * 여기로 오고 {@link ErrorResponseDto} 형식으로 나간다. nginx에 남은 20M은 한도가 아니라
     * 1GB 업로드를 앱까지 실어 나르지 않기 위한 바깥 울타리이며, 거기 걸리는 경우를 위해
     * {@code error_page 413}으로 같은 형식의 JSON을 내도록 해뒀다.
     *
     * <p><b>실제 크기를 응답에 넣지 않는다.</b> {@code getMaxUploadSize()}는 서버 설정값이고,
     * 그것을 알려주면 상한을 탐색하는 데 쓸 수 있다. 로그에는 남긴다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponseDto> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("업로드 용량 초과: 상한 {} bytes", e.getMaxUploadSize());
        return toResponse(ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE.getMessage());
    }

    /**
     * {@code @PreAuthorize} 등 메서드 보안에서 올라온 권한 거부.
     *
     * <p><b>아래 캐치올 때문에 반드시 필요하다.</b> {@link AccessDeniedException}은 {@code RuntimeException}이라
     * 이 핸들러가 없으면 캐치올이 삼켜 <b>403이어야 할 응답이 500</b>이 된다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponseDto> handleAccessDenied(AccessDeniedException e) {
        log.warn("접근 거부: {}", e.getMessage());
        return toResponse(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.getMessage());
    }

    /**
     * 마지막 그물. 여기 걸린다는 것은 <b>예상하지 못한 예외</b>라는 뜻이므로 스택 트레이스를 남긴다.
     *
     * <p>응답에는 예외 메시지를 넣지 않는다. 여기 오는 메시지는 대부분 내부 구현 정보다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return toResponse(ErrorCode.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private String message(ErrorCode errorCode, String detail) {
        return detail == null || detail.isBlank()
                ? errorCode.getMessage()
                : errorCode.getMessage() + " (" + detail + ")";
    }

    private ResponseEntity<ErrorResponseDto> toResponse(ErrorCode errorCode, String message) {
        ErrorResponseDto response = ErrorResponseDto.builder()
                .status(errorCode.getStatus())
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.valueOf(errorCode.getStatus())).body(response);
    }
}
