package com.DOCKin.worklog.model;

/** 작업일지 검토 상태 (P2-17-1). 수정하면 다시 {@link #PENDING}으로 돌아간다. */
public enum WorkLogStatus {
    PENDING,
    APPROVED,
    REJECTED
}
