package com.DOCKin.worklog.model;

/**
 * 작업일지 검토 상태 (P2-17-1). {@code AbsenceStatus}와 같은 셋이다.
 *
 * <p>휴가와 다른 점 하나 — 작성자가 수정하면 {@code PENDING}으로 돌아간다.
 * 반려 → 고침 → 재승인이 이 상태의 존재 이유이고, 승인된 일지를 고쳤는데 배지가 그대로면
 * 승인이 거짓이 된다. {@link WorkLog#resetReview()}.
 */
public enum WorkLogStatus {
    PENDING,
    APPROVED,
    REJECTED
}
