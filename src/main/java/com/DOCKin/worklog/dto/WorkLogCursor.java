package com.DOCKin.worklog.dto;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;

import java.time.LocalDateTime;

/**
 * 작업일지 목록의 커서 — 마지막으로 본 행의 {@code (createdAt, logId)}.
 *
 * <p>목록은 {@code created_at DESC, log_id DESC}로 고정돼 있고, 다음 페이지는 이 두 값보다
 * "앞선" 행부터다. 두 값 모두 응답 {@link WorkLogDto}에 실리므로 클라이언트는 마지막 원소의 것을
 * 그대로 넘긴다. 채팅의 {@code beforeSeq}와 같은 역할인데 채팅은 정렬 키가 하나라 값도 하나다.
 *
 * <p>둘 중 하나만 오면 400이다. {@code createdAt}만으로는 같은 시각의 행들 사이에서 어디까지
 * 봤는지 정해지지 않아 경계에서 행이 겹치거나 빠진다 — 컨트롤러가 정렬 키를 둘로 한 이유 그대로다.
 */
public record WorkLogCursor(LocalDateTime createdAt, Long logId) {

    /** 둘 다 없으면 첫 페이지(null). 하나만 있으면 잘못된 입력. */
    public static WorkLogCursor of(LocalDateTime createdAt, Long logId) {
        if (createdAt == null && logId == null) return null;
        if (createdAt == null || logId == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return new WorkLogCursor(createdAt, logId);
    }
}
