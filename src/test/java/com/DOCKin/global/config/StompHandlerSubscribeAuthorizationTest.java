package com.DOCKin.global.config;

import com.DOCKin.chat.presence.Presence;
import com.DOCKin.chat.repository.ChatMembersRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.security.jwt.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 검증: SUBSCRIBE가 <b>목적지의 주인</b>을 확인한다 (백로그 P2-18-3).
 *
 * <p>이전에는 로그인 여부만 봐서, 인증만 있으면 아무 방이나 구독해 실시간으로 다 받을 수 있었다.
 * 여기서 보는 것은 세 가지다 — 남의 방은 거부, 남의 방 목록은 거부, 목록에 없는 목적지는 거부.
 * 통과 조건은 하나다: 그 방의 멤버이거나 그 사용자 본인.
 */
@ExtendWith(MockitoExtension.class)
class StompHandlerSubscribeAuthorizationTest {

    private static final String ME = "u1";
    private static final String OTHER = "u2";
    private static final int MY_ROOM = 10;
    private static final int OTHER_ROOM = 20;

    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private ChatMembersRepository chatMembersRepository;
    @Mock
    private Presence presence;
    @Mock
    private MessageChannel channel;

    @InjectMocks
    private StompHandler handler;

    @Test
    @DisplayName("내가 멤버인 방은 구독된다")
    void 내_방() {
        when(chatMembersRepository.existsByChatRoomsRoomIdAndMemberUserId(MY_ROOM, ME)).thenReturn(true);

        Message<?> message = subscribe(ME, "/sub/chat/room/" + MY_ROOM);

        assertSame(message, handler.preSend(message, channel));
    }

    @Test
    @DisplayName("멤버가 아닌 방은 CT003으로 거부된다 - 도청 경로")
    void 남의_방() {
        when(chatMembersRepository.existsByChatRoomsRoomIdAndMemberUserId(OTHER_ROOM, ME)).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> handler.preSend(subscribe(ME, "/sub/chat/room/" + OTHER_ROOM), channel));

        assertEquals(ErrorCode.CHATROOM_AUTHOR, e.getErrorCode());
    }

    @Test
    @DisplayName("내 방 목록은 구독되고 남의 방 목록은 거부된다")
    void 방_목록() {
        Message<?> mine = subscribe(ME, "/sub/user/" + ME + "/rooms");
        assertSame(mine, handler.preSend(mine, channel));

        BusinessException e = assertThrows(BusinessException.class,
                () -> handler.preSend(subscribe(ME, "/sub/user/" + OTHER + "/rooms"), channel));
        assertEquals(ErrorCode.CHATROOM_AUTHOR, e.getErrorCode());
        // 방 목록은 멤버십 조회가 필요 없다 - 이름이 같은지만 본다.
        verify(chatMembersRepository, never()).existsByChatRoomsRoomIdAndMemberUserId(anyInt(), any());
    }

    @Test
    @DisplayName("목록에 없는 목적지는 전부 거부된다 - 열어 두고 잊는 것보다 닫아 두고 실패한다")
    void 모르는_목적지() {
        for (String destination : new String[]{
                "/sub/chat/room/", "/sub/chat/room/abc", "/sub/chat/room/10/extra",
                "/sub/user/u1/rooms/x", "/sub/broadcast", "/topic/anything", null}) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> handler.preSend(subscribe(ME, destination), channel), destination);
            assertEquals(ErrorCode.CHATROOM_AUTHOR, e.getErrorCode(), destination);
        }
    }

    @Test
    @DisplayName("CONNECT를 거치지 않은 세션(userId 없음)은 구독할 수 없다")
    void 미인증() {
        assertThrows(MessageDeliveryException.class,
                () -> handler.preSend(subscribe(null, "/sub/chat/room/" + MY_ROOM), channel));
    }

    // ------------------------------------------------------------------

    /** CONNECT에서 StompHandler가 세션 속성에 넣는 userId를 그대로 흉내 낸다. */
    private static Message<?> subscribe(String sessionUserId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("s1");
        if (destination != null) {
            accessor.setDestination(destination);
        }
        Map<String, Object> attrs = new HashMap<>();
        if (sessionUserId != null) {
            attrs.put("userId", sessionUserId);
        }
        accessor.setSessionAttributes(attrs);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
