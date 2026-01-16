package com.DOCKin.service;

import com.DOCKin.dto.chat.ChatRoomRequestDto;
import com.DOCKin.model.Chat.ChatRooms;
import com.DOCKin.repository.ChatMessagesRepository;
import com.DOCKin.repository.ChatRoomsRepository;
import com.DOCKin.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatRoomService {
    private final ChatMessagesRepository chatMessagesRepository;
    private final ChatRoomsRepository chatRoomsRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public Integer createChatRoom(ChatRoomRequestDto dto){
        ChatRooms savedRoom = ChatRooms.builder()
                .roomName(dto.getRoom_name())
                .isGroup(dto.getIs_group())
                .creatorId(dto.getCreatorId())
                .build();

        addMemberToRoom(savedRoom, dto.getCrea)
    }
}
