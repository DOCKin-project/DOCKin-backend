package com.DOCKin.absence.event;

import com.DOCKin.absence.model.AbsenceType;

import java.time.LocalDate;

/**
 * 승인된 휴가가 취소되었을 때 발행된다 — {@link AbsenceApprovedEvent}의 역.
 *
 * <p>승인이 만든 근태 행(VACATION/SICK)을 그대로 두면 그 기간은 휴가로 집계되고 결근 배치도 건너뛴다.
 * 취소는 시작일 전에만 되므로(서비스가 막는다) 그 행들은 아직 "일어나지 않은 날"이다 — 지워도 사실을 지우는 게 아니다.
 * 승인 이벤트와 같은 이유로 동기 리스너, 같은 트랜잭션이다.
 */
public record AbsenceCancelledEvent(
        String userId,
        AbsenceType type,
        LocalDate startDate,
        LocalDate endDate
) {}
