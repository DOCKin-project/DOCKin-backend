package com.DOCKin.chat.service;

import com.DOCKin.chat.dto.ChatMessageRequestDto;
import com.DOCKin.chat.dto.ChatMessageResponseDto;
import com.DOCKin.chat.dto.MessageType;
import com.DOCKin.chat.model.ChatMessages;
import com.DOCKin.chat.repository.ChatJdbcRepository;
import com.DOCKin.chat.repository.ChatMessagesRepository;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * V6 이후의 저장 경로가 약속한 것을 지키는가 (ADR-0008 D6·D7·D8·D9).
 *
 * <p>{@code MessageIdCommitOrderMeasurementTest}는 SQL을 직접 쳐서 <b>기전</b>을 쟀다.
 * 이 테스트는 실제 {@code ChatService.saveMessage}가 그 기전대로 짜였는지를 본다 —
 * 번호가 방 행 락 안에서 1부터 이어지는지, 방의 {@code last_message_seq}가 따라가는지,
 * {@code sent_at}을 앱이 아니라 DB가 채우는지, 같은 {@code client_msg_id}가 두 번 저장되지 않는지.
 *
 * <p>D1 이전에는 {@code saveMessage}가 {@code @Async}라 이 테스트가 실행기를 동기로 바꿔 끼워야 했다.
 * 이제 호출 스레드에서 끝나므로 그 우회가 없다. 전파는 {@code ChatBroadcasterTest}가 본다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChatService.class, ChatJdbcRepository.class})
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
// 테스트 트랜잭션으로 감싸지 않는다. 재전송_멱등이 유니크 위반을 일으키면 PostgreSQL은 그 트랜잭션을
// 통째로 중단시켜 뒤의 count 조회까지 실패한다. saveMessage가 자기 트랜잭션을 열고 닫게 둔다.
// 대신 커밋된 행이 컨테이너 DB에 남으므로 @AfterEach가 지우고, 사용자 ID도 이 테스트만의 것을 쓴다 —
// 처음엔 'u1'을 썼다가 같은 DB를 롤백 트랜잭션으로 쓰는 ChecklistResultRepositoryTest의 INSERT를 깨뜨렸다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChatServiceRoomSeqTest extends ContainerTestSupport {

    @Autowired private ChatService chatService;
    @Autowired private ChatMessagesRepository messages;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private static final String USER = "chat-seq-u1";
    private int roomId;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT INTO users (user_id, created_at, language_code, name, password,
                                   remaining_leave_days, role, ship_yard_area, tts_enabled)
                VALUES (:u, now(), 'vi', 'seq', 'x', 15, 'USER', 'A', false)
                ON CONFLICT (user_id) DO NOTHING
                """).param("u", USER).update();
        roomId = jdbc.sql("INSERT INTO chat_rooms (created_at, creator_id, is_group, room_name) VALUES (now(), :u, true, 'seq') RETURNING room_id")
                .param("u", USER).query(Integer.class).single();
        jdbc.sql("INSERT INTO chat_members (joined_at, room_id, user_id) VALUES (now(), :r, :u)").param("r", roomId).param("u", USER).update();
    }

    @AfterEach
    void cleanup() {
        // chat_messages는 room FK가 ON DELETE CASCADE라 방을 지우면 따라 지워진다. 멤버는 아니다.
        jdbc.sql("DELETE FROM chat_members WHERE room_id = :r").param("r", roomId).update();
        jdbc.sql("DELETE FROM chat_rooms WHERE room_id = :r").param("r", roomId).update();
        jdbc.sql("DELETE FROM users WHERE user_id = :u").param("u", USER).update();
    }

    @Test
    @DisplayName("room_seq는 1부터 이어지고 방의 last_message_seq가 그 값을 따라간다")
    void 방_시퀀스() {
        chatService.saveMessage(dto("a", null));
        chatService.saveMessage(dto("b", null));
        chatService.saveMessage(dto("c", null));

        List<Long> seqs = jdbc.sql("SELECT room_seq FROM chat_messages WHERE room_id = :r ORDER BY room_seq")
                .param("r", roomId).query(Long.class).list();
        assertEquals(List.of(1L, 2L, 3L), seqs);

        long roomSeq = jdbc.sql("SELECT last_message_seq FROM chat_rooms WHERE room_id = :r")
                .param("r", roomId).query(Long.class).single();
        assertEquals(3L, roomSeq, "방의 번호가 마지막 메시지의 번호와 같아야 한다");

        String last = jdbc.sql("SELECT last_message_content FROM chat_rooms WHERE room_id = :r")
                .param("r", roomId).query(String.class).single();
        assertEquals("c", last, "요약 컬럼 갱신이 같은 UPDATE에 있다");
    }

    @Test
    @DisplayName("sent_at은 DB가 채우고 언어는 발신자 설정에서 온다")
    void 시계와_언어() {
        // 저장과 조회를 한 트랜잭션에 둔다. 그래야 findById가 DB를 다시 읽지 않고 saveMessage가 만든
        // 그 인스턴스를 돌려주며, 그 인스턴스의 sentAt이 채워져 있는지가 곧 @Generated가 동작했는지다.
        // 트랜잭션을 나눠 읽으면 어차피 DB에서 읽어 오므로 아무것도 검증하지 못한다.
        ChatMessages saved = new TransactionTemplate(txManager).execute(tx -> {
            long id = chatService.saveMessage(dto("a", null)).getMessageId();
            return messages.findById(id).orElseThrow();
        });

        assertNotNull(saved.getSentAt(), "@Generated로 INSERT 뒤 읽어 와야 한다 - null이면 RETURNING이 안 붙은 것");
        LocalDateTime db = jdbc.sql("SELECT sent_at FROM chat_messages WHERE message_id = :id")
                .param("id", saved.getMessageId()).query(LocalDateTime.class).single();
        assertEquals(db, saved.getSentAt(), "엔티티의 값이 DB의 값과 같아야 시계가 하나다");
        assertEquals("vi", saved.getLanguageCode());
    }

    @Test
    @DisplayName("같은 client_msg_id는 같은 방에 두 번 저장되지 않는다")
    void 재전송_멱등() {
        UUID key = UUID.randomUUID();
        ChatMessageResponseDto first = chatService.saveMessage(dto("a", key));
        // 재전송은 예외가 아니라 같은 행이다(D9). 클라이언트는 응답을 못 받았을 뿐이므로 첫 저장의 ID를 받아야
        // "내 메시지가 갔다"를 확정할 수 있다. D1 이전에는 @Async가 유니크 위반을 삼켜 아무것도 돌아오지 않았다.
        ChatMessageResponseDto again = chatService.saveMessage(dto("a", key));
        assertEquals(first.getMessageId(), again.getMessageId());
        assertEquals(first.getRoomSeq(), again.getRoomSeq());
        long count = jdbc.sql("SELECT count(*) FROM chat_messages WHERE room_id = :r")
                .param("r", roomId).query(Long.class).single();
        assertEquals(1L, count);
    }

    private ChatMessageRequestDto dto(String content, UUID clientMsgId) {
        return ChatMessageRequestDto.builder()
                .roomId(roomId).senderId(USER).content(content)
                .messageType(MessageType.TEXT).clientMsgId(clientMsgId)
                .build();
    }
}
