package com.DOCKin.chat.controller;

import com.DOCKin.chat.dto.ChatMessageRequestDto;
import com.DOCKin.chat.dto.ChatSendErrorDto;
import com.DOCKin.chat.dto.MessageType;
import com.DOCKin.chat.service.ChatRoomService;
import com.DOCKin.chat.service.ChatService;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.UUID;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 검증: 메시지 발신이 <b>세션의 사용자</b>로, <b>멤버인 방에만</b> 간다 (백로그 P2-18-3),
 * 그리고 컨트롤러는 <b>전파하지 않는다</b> (ADR-0008 D1).
 *
 * <p>이전에는 본문의 senderId를 세션에 없을 때 그대로 썼고, roomId는 아무 검사 없이 전파·저장됐다.
 * D1 이후 전파는 커밋 뒤 {@code ChatBroadcaster}의 몫이라, 여기서 {@code messagingTemplate}이 불리는 경우는
 * 하나뿐이다 — 실패를 <b>보낸 사람에게만</b> 알릴 때({@code /sub/user/{id}/errors}).
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerMembershipTest {

    private static final int ROOM = 7;

    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private ChatService chatService;
    @Mock
    private ChatRoomService chatRoomService;

    @InjectMocks
    private ChatController controller;

    @Test
    @DisplayName("멤버가 아닌 방으로 보내면 저장되지 않고, 보낸 사람에게만 실패가 간다")
    void 비멤버_발신() {
        doThrow(new BusinessException(ErrorCode.CHATROOM_AUTHOR))
                .when(chatRoomService).validChatRoomMember("intruder", ROOM);
        ChatMessageRequestDto dto = request("intruder");
        dto.setClientMsgId(UUID.randomUUID());

        controller.message(dto, session("intruder"));

        verify(chatService, never()).saveMessage(any());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/sub/user/intruder/errors"), payload.capture());
        ChatSendErrorDto error = (ChatSendErrorDto) payload.getValue();
        assertEquals(ErrorCode.CHATROOM_AUTHOR.getCode(), error.code());
        assertEquals(dto.getClientMsgId(), error.clientMsgId(), "어느 메시지가 실패했는지는 clientMsgId로만 짝지을 수 있다");
        verify(messagingTemplate, never()).convertAndSend(eq("/sub/chat/room/" + ROOM), any(Object.class));
    }

    @Test
    @DisplayName("저장이 실패해도 방에는 아무것도 나가지 않고, 보낸 사람에게만 실패가 간다")
    void 저장_실패() {
        doThrow(new RuntimeException("db down")).when(chatService).saveMessage(any());

        controller.message(request("u1"), session("u1"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/sub/user/u1/errors"), payload.capture());
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), ((ChatSendErrorDto) payload.getValue()).code());
        verify(messagingTemplate, never()).convertAndSend(eq("/sub/chat/room/" + ROOM), any(Object.class));
    }

    @Test
    @DisplayName("본문의 senderId는 무시되고 세션의 사용자가 발신자가 된다. 컨트롤러는 전파하지 않는다")
    void 발신자_위조() {
        ChatMessageRequestDto dto = request("someone-else");

        controller.message(dto, session("u1"));

        assertEquals("u1", dto.getSenderId());
        verify(chatRoomService).validChatRoomMember("u1", ROOM);
        verify(chatService).saveMessage(dto);
        // 전파는 커밋 뒤 ChatBroadcaster가 한다. 여기서 나가면 저장 전에 보이는 옛 순서다.
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("세션에 사용자가 없으면(CONNECT 미경유) 본문의 senderId로 대신하지 않는다")
    void 미인증_세션() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.message(request("u1"), session(null)));

        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(chatService, never()).saveMessage(any());
    }

    // ------------------------------------------------------------------

    private static ChatMessageRequestDto request(String senderId) {
        ChatMessageRequestDto dto = new ChatMessageRequestDto();
        dto.setRoomId(ROOM);
        dto.setSenderId(senderId);
        dto.setContent("hi");
        dto.setMessageType(MessageType.TEXT);
        return dto;
    }

    private static SimpMessageHeaderAccessor session(String userId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        Map<String, Object> attrs = new HashMap<>();
        if (userId != null) {
            attrs.put("userId", userId);
        }
        accessor.setSessionAttributes(attrs);
        return accessor;
    }
}
