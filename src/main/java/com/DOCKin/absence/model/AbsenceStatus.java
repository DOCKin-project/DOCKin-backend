package com.DOCKin.absence.model;

public enum AbsenceStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /**
     * 신청자가 거둬들였다. 행은 남는다 — REJECTED와 같은 급의 종결 상태다.
     * 겹침(EXCLUDE·OCCUPYING)에서 자리를 차지하지 않는다. 승인 뒤 취소는 별도 PR(연차 환급·근태 행 삭제).
     */
    CANCELLED
}
