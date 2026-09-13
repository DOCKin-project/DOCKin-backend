package com.DOCKin.chat.controller;

import com.DOCKin.chat.dto.ChatMessageRequestDto;
import com.DOCKin.chat.service.ChatRoomService;
import com.DOCKin.chat.service.ChatService;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.List;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatController {
    private final SimpMessagingTemplate messagingTemplate;
    private final ChatService chatService;
    private final ChatRoomService chatRoomService;

    @MessageMapping("/chat/message")
    public void message(ChatMessageRequestDto message, SimpMessageHeaderAccessor headerAccessor){

        // 발신자는 세션이 정한다. 이전에는 세션에 없으면 본문의 senderId를 그대로 썼다 —
        // 그러면 아무나 남의 이름으로 보낼 수 있다. 세션에 없으면 CONNECT를 거치지 않은 것이다.
        String actualUserId = headerAccessor.getSessionAttributes() == null
                ? null
                : (String) headerAccessor.getSessionAttributes().get("userId");
        if (actualUserId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        message.setSenderId(actualUserId);

        // 방의 멤버만 보낸다. 이전에는 아무 roomId로나 보내면 그 방에 전파되고 저장됐다(P2-18-3).
        // 여기서 던진 예외는 @MessageExceptionHandler가 없어 로그에만 남고 아무것도 전파되지 않는다 —
        // 보낸 사람에게 ERROR 프레임으로 알리는 것은 ADR-0008 3절이 다룬다.
        chatRoomService.validChatRoomMember(actualUserId, message.getRoomId());

        log.info("메시지 수신: 방번호={}, 보낸이={}, 내용={}",
                message.getRoomId(),message.getSenderId(),message.getContent());

        messagingTemplate.convertAndSend("/sub/chat/room/" + message.getRoomId(), message);

        List<String> memberIds = chatRoomService.getParticipantsIds(message.getRoomId());

        for (String userId : memberIds) {
            messagingTemplate.convertAndSend("/sub/user/" + userId + "/rooms", message);
        }
        
        chatService.saveMessage(message);
    }


}
