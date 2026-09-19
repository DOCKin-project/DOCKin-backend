package com.DOCKin.checklist.model;

/** 닫힌 회차의 결말. 열려 있는 동안은 null이다 — 상태 셋 중 IN_PROGRESS만 컬럼이 아니라 "outcome이 없음"으로 표현된다. */
public enum ChecklistRunOutcome {
    /** 전 항목 체크 뒤 점검자가 완료를 눌렀다. */
    COMPLETED,
    /** 완료 없이 놓았다. 12시간 뒤 같은 사람이 같은 템플릿을 다시 열 때 닫힌다({@code ChecklistRun.OPEN_SPAN}). */
    ABANDONED
}
