package com.DOCKin.chat.service;

import com.DOCKin.chat.dto.ChatMessageRequestDto;
import com.DOCKin.chat.dto.ChatMessageResponseDto;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.chat.model.ChatMembers;
import com.DOCKin.chat.model.ChatMessages;
import com.DOCKin.chat.model.ChatRooms;
import com.DOCKin.chat.repository.ChatJdbcRepository;
import com.DOCKin.chat.repository.ChatMembersRepository;
import com.DOCKin.chat.repository.ChatMessagesRepository;
import com.DOCKin.chat.repository.ChatRoomsRepository;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {
    private final ChatMessagesRepository chatMessagesRepository;
    private final ChatRoomsRepository chatRoomsRepository;
    private final ChatMembersRepository chatMembersRepository;
    private final ChatJdbcRepository chatJdbcRepository;
    private final MemberRepository memberRepository;

    @Async
    @Transactional
    public void saveMessage(ChatMessageRequestDto dto) {
        log.info("### [시작] 동기 방식으로 실행");

        ChatRooms room = chatRoomsRepository.findById(dto.getRoomId())
                .orElseThrow(() -> new RuntimeException("방 없음"));

        // 1. 방 행을 잠그고 번호를 받는다. INSERT보다 먼저여야 한다 — 번호를 락 밖에서 받으면
        //    커밋 순서와 어긋난다(ADR-0008 5-4, V6). 요약 컬럼 갱신도 이 한 문장에 있다.
        //    위에서 올린 room 객체는 이 UPDATE 뒤에도 옛 값이므로 setter로 고치면 안 된다.
        long roomSeq = chatJdbcRepository.nextRoomSeq(dto.getRoomId(), dto.getContent());
        log.info("### [1] 방 시퀀스 발급 {}", roomSeq);

        // 2. 메시지 저장은 JPA. sent_at은 DB 기본값이 채우고(D6) Hibernate가 RETURNING으로 읽어 온다.
        //    언어는 발신자 설정을 기본값으로 남긴다(P2-8-5) — 소급이 안 되는 값이라 번역 기능보다 먼저다.
        String languageCode = memberRepository.findById(dto.getSenderId())
                .map(Member::getLanguage_code)
                .orElse(null);
        ChatMessages msg = ChatMessages.builder()
                .chatRooms(room)
                .senderId(dto.getSenderId())
                .content(dto.getContent())
                .messageType(dto.getMessageType())
                .roomSeq(roomSeq)
                .clientMsgId(dto.getClientMsgId())
                .languageCode(languageCode)
                .build();
        chatMessagesRepository.saveAndFlush(msg);
        log.info("### [2] 메시지 저장 완료 id={}", msg.getMessageId());

        // 3. 자기가 보낸 것은 읽은 것이다. last_read_time은 V7까지 병행한다.
        chatJdbcRepository.updateLastReadTime(dto.getRoomId(), dto.getSenderId());
        log.info("### [3] 멤버 업데이트(JDBC) 완료");
    }

    //이전 채팅 내역 불러오기
    @Transactional(readOnly = true)
    public Slice<ChatMessageResponseDto> getChatHistory(Integer roomId,String userId, Long lastMessageId,Pageable pageable){
       ChatMembers memberInfo = chatMembersRepository.findByChatRoomsRoomIdAndMemberUserId(roomId,userId)
               .orElseThrow(()->new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

       Slice<ChatMessages> messages = chatMessagesRepository.findChatHistory(
               roomId,
               memberInfo.getJoinedAt(),
               lastMessageId,
               pageable
       );
        return messages.map(ChatMessageResponseDto::from);
    }

    //메시지 검색
    @Transactional(readOnly = true)
    public Slice<ChatMessageResponseDto> searchMessage(Integer roomId, String userId ,String keyword ,Pageable pageable){
        ChatMembers members = chatMembersRepository.findByChatRoomsRoomIdAndMemberUserId(roomId,userId)
                .orElseThrow(()->new BusinessException(ErrorCode.CHATMEMBER_NOT_FOUND));

        Slice<ChatMessages> messages = chatMessagesRepository
                .searchMessageByKeyword(
                        roomId,
                        members.getJoinedAt(),
                        keyword,
                        pageable
                );
        return messages.map(ChatMessageResponseDto::from);
    }
}
