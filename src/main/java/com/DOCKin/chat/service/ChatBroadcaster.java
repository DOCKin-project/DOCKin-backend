package com.DOCKin.chat.service;

import com.DOCKin.chat.dto.ChatMessageResponseDto;
import com.DOCKin.chat.event.ChatMessageSaved;
import com.DOCKin.chat.repository.ChatJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 저장이 커밋된 메시지를 WebSocket으로 전파한다 (ADR-0008 D1·D2).
 *
 * <h3>왜 커밋 뒤인가</h3>
 * 이전에는 {@code ChatController}가 전파를 먼저 하고 저장을 {@code @Async}로 뒤에 했다. 저장이 실패해도
 * 수신자는 이미 봤고, 새로고침하면 사라졌으며, 실패는 로그에만 남았다. 전파 페이로드는 요청 DTO라
 * {@code message_id}도 {@code sent_at}도 없어 수신자가 중복을 걸러낼 키도 커서도 없었다(ADR-0008 1절).
 * 이제 순서가 반대다 — 저장 → 커밋 → 이 리스너. <b>보이는 것은 저장된 것이다.</b>
 *
 * <h3>실패의 반경</h3>
 * 저장이 실패하면 이 리스너는 불리지 않는다. 다른 사람은 애초에 못 봤으므로 알릴 것이 없고,
 * 보낸 사람에게만 {@code ChatController}가 알린다. 여기서 전파가 실패하면(세션이 끊긴 뒤 등)
 * 메시지는 이미 저장돼 있으므로 재접속 따라잡기가 채운다 — 전파 실패가 저장을 되돌리지 않는다.
 *
 * <h3>멤버 조회</h3>
 * 방 목록 갱신 전파는 멤버마다 한 번이다. 이전에는 {@code getParticipantsIds}가 메시지마다
 * {@code chat_members}를 읽고 행마다 {@code getMember().getUserId()}로 LAZY 역참조해 10명 방이면
 * 쿼리 11개가 STOMP 인바운드 스레드에서 돌았다. 여기서는 {@code user_id}만 뽑는 SQL 하나다.
 *
 * <p>같은 이벤트의 다른 소비자(번역 D3, 푸시 D10)는 아직 없다. 붙을 자리는 이 클래스 옆이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatJdbcRepository chatJdbcRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ChatMessageSaved event) {
        ChatMessageResponseDto message = event.message();
        Integer roomId = message.getRoomId();

        messagingTemplate.convertAndSend("/sub/chat/room/" + roomId, message);

        List<String> memberIds = chatJdbcRepository.memberIds(roomId);
        for (String userId : memberIds) {
            messagingTemplate.convertAndSend("/sub/user/" + userId + "/rooms", message);
        }
        log.debug("전파 완료: room={}, seq={}, members={}", roomId, message.getRoomSeq(), memberIds.size());
    }
}
