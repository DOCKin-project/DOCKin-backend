package com.DOCKin.absence.event;

import com.DOCKin.absence.model.AbsenceType;

import java.time.LocalDate;

/**
 * 휴가/병가 신청이 승인되었을 때 발행된다.
 *
 * <p><b>왜 이벤트인가</b> — 휴가 승인은 근태에 반영되어야 하지만, 휴가 도메인이 근태 도메인을
 * 직접 호출하면 두 모듈이 결합된다. 앞으로 승인 시 알림 발송 같은 후속 처리가 늘어날수록
 * {@code AbsenceRequestService}가 계속 비대해진다. 발행자는 "승인됐다"는 사실만 알리고,
 * 무엇을 할지는 각 모듈이 구독해서 결정한다.
 *
 * <p><b>다만 트랜잭션은 분리하지 않는다.</b> 리스너를 {@code @EventListener}(동기)로 두어
 * 승인과 같은 트랜잭션에서 실행되게 한다. 근태 반영이 실패했는데 승인만 남으면
 * <b>그날은 승인된 휴가인데도 무단 결근으로 처리</b>되기 때문이다.
 * 결합도만 낮추고 원자성은 유지하는 선택이다.
 *
 * @param userId    신청자 사번
 * @param type      휴가 종류. 근태 상태값(VACATION/SICK)으로 매핑된다
 * @param startDate 시작일 (포함)
 * @param endDate   종료일 (포함)
 */
public record AbsenceApprovedEvent(
        String userId,
        AbsenceType type,
        LocalDate startDate,
        LocalDate endDate
) {}
