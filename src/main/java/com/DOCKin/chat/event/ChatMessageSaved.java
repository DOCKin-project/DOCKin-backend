package com.DOCKin.chat.event;

import com.DOCKin.chat.dto.ChatMessageResponseDto;

/**
 * 메시지 한 건이 저장 트랜잭션 안에서 발행하는 사실. <b>소비자는 커밋 뒤에만 듣는다</b>
 * ({@code @TransactionalEventListener(AFTER_COMMIT)}, ADR-0008 D1).
 *
 * <p>휴가 승인 → 근태 반영(P2)은 같은 {@code ApplicationEvent} 장치를 <b>같은 트랜잭션</b>에서 듣는다 —
 * 근태 반영이 실패하면 승인도 실패해야 하기 때문이다. 여기는 반대다. 전파·번역·푸시가 실패해도
 * 저장이 되돌아가면 안 되고, 저장이 되돌아갔는데 전파가 나가면 안 된다. 기준은 하나다:
 * <b>함께 실패해야 하는가.</b>
 *
 * <p>실어 보내는 것은 요청 DTO가 아니라 <b>DB가 준 값</b>이 들어간 응답 DTO다 — {@code messageId},
 * {@code roomSeq}, {@code sentAt}. 수신자가 중복을 걸러내고 재접속 커서를 잡는 축이 여기서 나온다(D2).
 */
public record ChatMessageSaved(ChatMessageResponseDto message) {
}
