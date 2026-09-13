package com.DOCKin.chat.service;

import com.DOCKin.chat.dto.ChatMessageRequestDto;
import com.DOCKin.chat.dto.ChatMessageResponseDto;
import com.DOCKin.chat.dto.ChatRoomResponseDto;
import com.DOCKin.chat.dto.MessageType;
import com.DOCKin.chat.repository.ChatJdbcRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 읽음·따라잡기·방 목록이 전부 {@code room_seq} 하나를 축으로 도는가 (ADR-0008 11-1).
 *
 * <ul>
 *   <li><b>따라잡기</b>: {@code afterSeq}보다 큰 것을 오래된 순으로, {@code hasNext}로 이어진다</li>
 *   <li><b>읽음</b>: {@code GREATEST}라 작은 값이 큰 값을 되돌리지 않는다. 발신자 본인은 저장 때 자동으로 올라간다</li>
 *   <li><b>방 목록</b>: 안읽음은 COUNT가 아니라 뺄셈이고, 최근 메시지 순이다</li>
 * </ul>
 *
 * <p>{@code SimpMessagingTemplate}은 목이다 — 여기서 보는 것은 DB 쪽이지 전파가 아니다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChatService.class, ChatRoomService.class, ChatJdbcRepository.class, ChatBroadcaster.class})
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChatReadCatchUpTest extends ContainerTestSupport {

    private static final String A = "rc-a";
    private static final String B = "rc-b";

    @MockitoBean private SimpMessagingTemplate messagingTemplate;

    @Autowired private ChatService chatService;
    @Autowired private ChatRoomService chatRoomService;
    @Autowired private JdbcClient jdbc;

    private final List<Integer> rooms = new ArrayList<>();

    @BeforeEach
    void seed() {
        for (String u : List.of(A, B)) {
            jdbc.sql("""
                    INSERT INTO users (user_id, created_at, language_code, name, password,
                                       remaining_leave_days, role, ship_yard_area, tts_enabled)
                    VALUES (:u, now(), 'ko', 'rc', 'x', 15, 'USER', 'A', false)
                    ON CONFLICT (user_id) DO NOTHING
                    """).param("u", u).update();
        }
    }

    @AfterEach
    void cleanup() {
        for (Integer r : rooms) {
            jdbc.sql("DELETE FROM chat_members WHERE room_id = :r").param("r", r).update();
            jdbc.sql("DELETE FROM chat_rooms WHERE room_id = :r").param("r", r).update();
        }
        rooms.clear();
        jdbc.sql("DELETE FROM users WHERE user_id IN (:a, :b)").param("a", A).param("b", B).update();
    }

    @Test
    @DisplayName("따라잡기: afterSeq보다 큰 것만, 오래된 순으로, hasNext로 이어진다")
    void 따라잡기() {
        int room = room("catch");
        for (int i = 1; i <= 5; i++) chatService.saveMessage(dto(room, A, "m" + i));

        Slice<ChatMessageResponseDto> first = chatService.catchUp(room, B, 2, 2);
        assertEquals(List.of(3L, 4L), first.map(ChatMessageResponseDto::getRoomSeq).getContent());
        assertTrue(first.hasNext());

        Slice<ChatMessageResponseDto> second = chatService.catchUp(room, B, 4, 2);
        assertEquals(List.of(5L), second.map(ChatMessageResponseDto::getRoomSeq).getContent());
        assertFalse(second.hasNext());

        assertTrue(chatService.catchUp(room, B, 5, 2).isEmpty(), "다 받았으면 빈 슬라이스다");
    }

    @Test
    @DisplayName("따라잡기는 멤버만 부를 수 있다")
    void 따라잡기_비멤버() {
        int room = room("private", A);
        assertThrows(BusinessException.class, () -> chatService.catchUp(room, B, 0, 10));
    }

    @Test
    @DisplayName("읽음: 발신자는 자동으로 올라가고, 상대는 안읽음이 세지지 않고 빼진다")
    void 읽음과_안읽음() {
        int room = room("read");
        for (int i = 1; i <= 4; i++) chatService.saveMessage(dto(room, A, "m" + i));

        assertEquals(0L, unread(A, room), "자기가 보낸 것은 읽은 것이다 - 저장 때 last_read_seq가 따라간다");
        assertEquals(4L, unread(B, room), "last_message_seq 4 − last_read_seq 0");

        chatService.markRead(room, B, 3);
        assertEquals(1L, unread(B, room));

        chatService.markRead(room, B, 2);
        assertEquals(1L, unread(B, room), "GREATEST - 늦게 도착한 작은 값은 큰 값을 되돌리지 않는다");

        chatService.markRead(room, B, 3);
        assertEquals(1L, unread(B, room), "같은 값이 두 번 와도 같다(멱등)");

        chatService.markRead(room, B, 99);
        assertEquals(0L, unread(B, room), "방의 번호를 넘겨도 음수가 되지 않는다");
    }

    @Test
    @DisplayName("읽음 처리는 멤버만 할 수 있다")
    void 읽음_비멤버() {
        int room = room("private2", A);
        assertThrows(BusinessException.class, () -> chatService.markRead(room, B, 1));
    }

    @Test
    @DisplayName("방 상세 조회는 더 이상 읽음 처리를 하지 않는다")
    void 상세조회_부수효과_없음() {
        int room = room("detail");
        chatService.saveMessage(dto(room, A, "m1"));
        chatService.saveMessage(dto(room, A, "m2"));

        ChatRoomResponseDto info = chatRoomService.getChatRoomsInfo(B, room);
        assertEquals(2L, info.getUnreadCount());
        assertEquals(2L, info.getLastMessageSeq());
        assertEquals(2L, unread(B, room), "조회했다고 읽은 것이 되지 않는다 - 읽음은 PATCH /read가 명시한다");
    }

    @Test
    @DisplayName("방 목록: 최근 메시지 순이고, 메시지 없는 방은 뒤로, 참가자는 한 번에 온다")
    void 방_목록_정렬() {
        int older = room("older");
        int empty = room("empty");
        int newer = room("newer");
        chatService.saveMessage(dto(older, A, "x"));
        chatService.saveMessage(dto(newer, A, "y"));

        Page<ChatRoomResponseDto> page = chatRoomService.getChatRooms(B, PageRequest.of(0, 10));
        List<Integer> order = page.map(ChatRoomResponseDto::getRoomId).getContent();
        // 같은 DB에 다른 테스트의 방이 남아 있을 수 있으므로 내 방 셋의 상대 순서만 본다.
        assertTrue(order.indexOf(newer) < order.indexOf(older), "최근 메시지가 위");
        assertTrue(order.indexOf(older) < order.indexOf(empty), "메시지 없는 방은 뒤");
        assertEquals(3, page.getTotalElements());

        ChatRoomResponseDto newest = page.getContent().get(order.indexOf(newer));
        assertEquals(List.of(A, B), newest.getParticipantIds());
        assertEquals(1L, newest.getUnreadCount());
    }

    // ------------------------------------------------------------------

    private int room(String name, String... members) {
        String[] who = members.length == 0 ? new String[]{A, B} : members;
        int roomId = jdbc.sql("INSERT INTO chat_rooms (created_at, creator_id, is_group, room_name) VALUES (now(), :u, true, :n) RETURNING room_id")
                .param("u", who[0]).param("n", name).query(Integer.class).single();
        for (String u : who) {
            jdbc.sql("INSERT INTO chat_members (joined_at, room_id, user_id) VALUES (now() - interval '1 minute', :r, :u)")
                    .param("r", roomId).param("u", u).update();
        }
        rooms.add(roomId);
        return roomId;
    }

    private long unread(String userId, int roomId) {
        return chatRoomService.getChatRooms(userId, PageRequest.of(0, 50)).getContent().stream()
                .filter(r -> r.getRoomId().equals(roomId))
                .findFirst().orElseThrow()
                .getUnreadCount();
    }

    private static ChatMessageRequestDto dto(int roomId, String sender, String content) {
        return ChatMessageRequestDto.builder()
                .roomId(roomId).senderId(sender).content(content).messageType(MessageType.TEXT)
                .build();
    }
}
