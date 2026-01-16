package com.DOCKin.service;

import com.DOCKin.dto.chat.ChatRoomRequestDto;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.model.Chat.ChatMembers;
import com.DOCKin.model.Chat.ChatRooms;
import com.DOCKin.model.Member.Member;
import com.DOCKin.repository.ChatMembersRepository;
import com.DOCKin.repository.ChatMessagesRepository;
import com.DOCKin.repository.ChatRoomsRepository;
import com.DOCKin.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatRoomService {
    private final ChatRoomsRepository chatRoomsRepository;
    private final ChatMembersRepository chatMembersRepository;
    private final MemberRepository memberRepository;

    //채팅방 개설
    @Transactional
    public Integer createChatRoom(ChatRoomRequestDto dto,String creatorId){
        ChatRooms rooms = ChatRooms.builder()
                .roomName(dto.getRoom_name())
                .isGroup(dto.getIs_group())
                .creatorId(dto.getCreatorId())
                .build();

        ChatRooms savedRoom = chatRoomsRepository.save(rooms);

        //방장을 첫 번째 멤버로 저장
        saveMember(savedRoom,creatorId);

        //초대받은 참여자들 저장
        if(dto.getParticipantIds()!=null){
            dto.getParticipantIds().stream()
                    .filter(userId -> !userId.equals(creatorId))
                    .forEach(userId->saveMember(savedRoom,userId));

        }
        return  savedRoom.getRoomId();
    }


    private void saveMember(ChatRooms rooms, String userId){
        Member memberEntity = memberRepository.findById(userId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

        ChatMembers chatMember = ChatMembers.builder()
                .chatRooms(rooms)
                .member(memberEntity)
                .joinedAt(LocalDateTime.now())
                .build();
        chatMembersRepository.save(chatMember);
    }
}
