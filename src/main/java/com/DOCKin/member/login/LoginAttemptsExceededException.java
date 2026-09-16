package com.DOCKin.member.login;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import lombok.Getter;

/**
 * 창 안의 로그인 실패가 상한에 닿았다. {@code GlobalExceptionHandler}가 429와 {@code Retry-After}(창이 끝날 때까지 초)를 붙인다.
 * {@code AiQuotaExceededException}과 같은 꼴 — 본문은 다른 {@link BusinessException}과 같고 헤더 하나만 더 얹는다.
 */
@Getter
public class LoginAttemptsExceededException extends BusinessException {

    private final long retryAfterSeconds;

    public LoginAttemptsExceededException(long retryAfterSeconds) {
        super(ErrorCode.LOGIN_ATTEMPTS_EXCEEDED);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
