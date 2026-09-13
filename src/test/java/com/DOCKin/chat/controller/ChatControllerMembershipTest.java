package com.DOCKin.chat.controller;

import com.DOCKin.chat.dto.ChatMessageRequestDto;
import com.DOCKin.chat.dto.MessageType;
import com.DOCKin.chat.service.ChatRoomService;
import com.DOCKin.chat.service.ChatService;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 검증: 메시지 발신이 <b>세션의 사용자</b>로, <b>멤버인 방에만</b> 간다 (백로그 P2-18-3).
 *
 * <p>이전에는 본문의 senderId를 세션에 없을 때 그대로 썼고, roomId는 아무 검사 없이 전파·저장됐다.
 * 순서가 중요하다 — 멤버십 검사는 전파보다 <b>앞</b>에 있어야 한다. 뒤에 있으면 이미 나간 뒤다.
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
    @DisplayName("멤버가 아닌 방으로 보내면 전파도 저장도 일어나지 않는다")
    void 비멤버_발신() {
        doThrow(new BusinessException(ErrorCode.CHATROOM_AUTHOR))
                .when(chatRoomService).validChatRoomMember("intruder", ROOM);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.message(request("intruder"), session("intruder")));

        assertEquals(ErrorCode.CHATROOM_AUTHOR, e.getErrorCode());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(chatService, never()).saveMessage(any());
    }

    @Test
    @DisplayName("본문의 senderId는 무시되고 세션의 사용자가 발신자가 된다")
    void 발신자_위조() {
        when(chatRoomService.getParticipantsIds(ROOM)).thenReturn(List.of("u1"));
        ChatMessageRequestDto dto = request("someone-else");

        controller.message(dto, session("u1"));

        assertEquals("u1", dto.getSenderId());
        verify(chatRoomService).validChatRoomMember("u1", ROOM);
        verify(messagingTemplate).convertAndSend("/sub/chat/room/" + ROOM, dto);
        verify(chatService).saveMessage(dto);
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
