package com.DOCKin.worklog.event;

import com.DOCKin.worklog.model.WorkLogStatus;

/**
 * 작업일지가 승인되거나 반려됐다 (P2-17-1). 검토 트랜잭션 안에서 발행된다.
 *
 * <p><b>소비자는 아직 없다.</b> PPT 15P의 "승인·반려 실시간 반영"은 FCM(ADR-0008 D10, P2-12-6)이
 * 붙을 때 이 이벤트를 받는 것으로 한다 — 채팅 메시지 저장이 {@code ChatMessageSaved}로 전파·번역·푸시
 * 소비자를 받는 것과 같은 자리다. 지금 클라이언트는 조회로 상태를 본다.
 *
 * <p><b>소비자는 {@code @TransactionalEventListener(phase = AFTER_COMMIT)}로 받는다.</b> 롤백된 검토의
 * 알림이 나가면 안 되고, 알림 실패가 검토를 되돌리면 안 된다 — {@code AbsenceApprovedEvent}가 근태 반영을
 * <i>같은</i> 트랜잭션에 둔 것과 반대인데, 기준은 하나다: 함께 실패해야 하는가. 근태 반영은 그렇고
 * 알림은 아니다.
 *
 * <p>카프카 같은 브로커는 두지 않는다. 소비자가 같은 JVM에 있고, 승인은 DB에 상태로 남아 있어
 * 알림이 빠져도 조회로 보인다. 유실이 정말 문제가 되면 답은 브로커가 아니라 outbox 행이다.
 *
 * @param logId        작업일지
 * @param authorUserId 작성자 — 알림을 받을 사람
 * @param status       APPROVED 또는 REJECTED
 * @param reviewedBy   검토한 관리자
 * @param comment      승인은 null일 수 있고 반려는 항상 있다
 */
public record WorkLogReviewed(Long logId, String authorUserId, WorkLogStatus status,
                              String reviewedBy, String comment) {}
