package com.DOCKin.ai.quota;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import lombok.Getter;

/**
 * 오늘 한도를 넘었다. {@code GlobalExceptionHandler}가 429와 함께 {@code Retry-After}(자정까지 초)를 붙인다.
 *
 * <p>{@link BusinessException}을 상속하는 이유는 응답 본문 형식을 바꾸지 않기 위해서다 —
 * 핸들러가 헤더 하나만 더 얹는다.
 */
@Getter
public class AiQuotaExceededException extends BusinessException {

    private final AiQuotaKind kind;
    private final long retryAfterSeconds;

    public AiQuotaExceededException(AiQuotaKind kind, long retryAfterSeconds) {
        super(ErrorCode.AI_QUOTA_EXCEEDED);
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
