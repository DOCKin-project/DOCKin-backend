package com.DOCKin.chat.service;

import com.DOCKin.chat.dto.ChatMessageRequestDto;
import com.DOCKin.chat.dto.ChatMessageResponseDto;
import com.DOCKin.chat.dto.MessageType;
import com.DOCKin.chat.repository.ChatJdbcRepository;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 전파는 커밋 뒤에만, DB가 준 값으로 (ADR-0008 D1·D2).
 *
 * <p>둘을 본다. <b>커밋되면</b> 방과 멤버에게 {@code messageId}·{@code roomSeq}·{@code sentAt}이 든
 * 페이로드가 나간다. <b>롤백되면</b> 아무것도 나가지 않는다 — 이전 순서(전파 → 비동기 저장)에서는
 * 저장이 실패해도 수신자가 이미 본 뒤였고, 그것이 D1이 뒤집은 것이다.
 *
 * <p>롤백은 바깥 트랜잭션을 열고 {@code setRollbackOnly}로 만든다. {@code saveMessage}의
 * {@code TransactionTemplate}은 기본 전파(REQUIRED)라 바깥에 합류하고, 커밋이 없으니
 * {@code AFTER_COMMIT} 리스너가 불릴 일이 없다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChatService.class, ChatJdbcRepository.class, ChatBroadcaster.class})
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChatBroadcasterTest extends ContainerTestSupport {

    private static final String SENDER = "bc-u1";
    private static final String OTHER = "bc-u2";

    @MockitoBean private SimpMessagingTemplate messagingTemplate;

    @Autowired private ChatService chatService;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private int roomId;

    @BeforeEach
    void seed() {
        for (String u : List.of(SENDER, OTHER)) {
            jdbc.sql("""
                    INSERT INTO users (user_id, created_at, language_code, name, password,
                                       remaining_leave_days, role, ship_yard_area, tts_enabled)
                    VALUES (:u, now(), 'ko', 'bc', 'x', 15, 'USER', 'A', false)
                    ON CONFLICT (user_id) DO NOTHING
                    """).param("u", u).update();
        }
        roomId = jdbc.sql("INSERT INTO chat_rooms (created_at, creator_id, is_group, room_name) VALUES (now(), :u, true, 'bc') RETURNING room_id")
                .param("u", SENDER).query(Integer.class).single();
        for (String u : List.of(SENDER, OTHER)) {
            jdbc.sql("INSERT INTO chat_members (joined_at, room_id, user_id) VALUES (now(), :r, :u)").param("r", roomId).param("u", u).update();
        }
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("DELETE FROM chat_members WHERE room_id = :r").param("r", roomId).update();
        jdbc.sql("DELETE FROM chat_rooms WHERE room_id = :r").param("r", roomId).update();
        jdbc.sql("DELETE FROM users WHERE user_id IN (:a, :b)").param("a", SENDER).param("b", OTHER).update();
    }

    @Test
    @DisplayName("커밋되면 방과 멤버 모두에게 DB가 준 값(messageId·roomSeq·sentAt)이 나간다")
    void 커밋_후_전파() {
        ChatMessageResponseDto saved = chatService.saveMessage(dto("hello"));

        ArgumentCaptor<Object> room = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/sub/chat/room/" + roomId), room.capture());
        ChatMessageResponseDto sent = (ChatMessageResponseDto) room.getValue();

        assertNotNull(sent.getMessageId(), "요청 DTO가 아니라 저장된 행이어야 한다");
        assertEquals(1L, sent.getRoomSeq());
        assertNotNull(sent.getSentAt(), "sent_at은 DB가 채운 값이 RETURNING으로 실려 온다");
        assertEquals(saved.getMessageId(), sent.getMessageId(), "호출자가 받은 것과 전파된 것이 같은 행이다");

        verify(messagingTemplate).convertAndSend(eq("/sub/user/" + SENDER + "/rooms"), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq("/sub/user/" + OTHER + "/rooms"), any(Object.class));
    }

    @Test
    @DisplayName("롤백되면 아무에게도 나가지 않는다")
    void 롤백_시_침묵() {
        TransactionTemplate outer = new TransactionTemplate(txManager);
        outer.executeWithoutResult(tx -> {
            chatService.saveMessage(dto("never"));
            tx.setRollbackOnly();
        });

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        long count = jdbc.sql("SELECT count(*) FROM chat_messages WHERE room_id = :r").param("r", roomId).query(Long.class).single();
        assertEquals(0L, count, "롤백됐으니 행도 없다 - 행이 없는데 전파가 나갔다면 D1 이전의 상태다");
    }

    @Test
    @DisplayName("재전송으로 기존 행을 돌려줄 때는 다시 전파하지 않는다")
    void 재전송은_전파_없음() {
        ChatMessageRequestDto dto = dto("once");
        dto.setClientMsgId(java.util.UUID.randomUUID());
        chatService.saveMessage(dto);
        chatService.saveMessage(dto);

        // 방 토픽에 정확히 한 번. 두 번 나가면 수신자가 같은 메시지를 두 번 본다.
        verify(messagingTemplate).convertAndSend(eq("/sub/chat/room/" + roomId), any(Object.class));
    }

    private ChatMessageRequestDto dto(String content) {
        return ChatMessageRequestDto.builder()
                .roomId(roomId).senderId(SENDER).content(content).messageType(MessageType.TEXT)
                .build();
    }
}
