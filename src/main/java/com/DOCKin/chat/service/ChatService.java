package com.DOCKin.chat.service;

import com.DOCKin.chat.dto.ChatMessageRequestDto;
import com.DOCKin.chat.dto.ChatMessageResponseDto;
import com.DOCKin.chat.event.ChatMessageSaved;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {
    private final ChatMessagesRepository chatMessagesRepository;
    private final ChatRoomsRepository chatRoomsRepository;
    private final ChatMembersRepository chatMembersRepository;
    private final ChatJdbcRepository chatJdbcRepository;
    private final MemberRepository memberRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 메시지 한 건을 저장하고, 커밋되면 전파된다 (ADR-0008 D1·D2·D9).
     *
     * <h3>동기다 — {@code @Async}를 뗐다</h3>
     * 이전에는 전파가 먼저 나가고 저장이 다른 스레드에서 뒤따랐다. 저장 실패는 로그에만 남았고
     * ({@code AsyncUncaughtExceptionHandler}), 실행기 큐가 차면 {@code RejectedExecutionException}으로
     * 조용히 유실됐다. 호출 스레드에서 끝나야 실패가 호출자에게 돌아오고, 호출자가 보낸 사람에게 알릴 수 있다.
     *
     * <h3>전파는 이 메서드가 하지 않는다</h3>
     * 트랜잭션 안에서 {@link ChatMessageSaved}를 발행하고, {@code ChatBroadcaster}가 커밋 뒤에 듣는다.
     * 롤백되면 아무도 듣지 않는다. 보이는 것은 저장된 것이어야 한다.
     *
     * <h3>재전송은 같은 행을 돌려준다 (D9)</h3>
     * {@code clientMsgId}가 있으면 먼저 찾아보고, 있으면 저장하지 않고 그 행을 돌려준다. 두 재전송이
     * 동시에 들어와 둘 다 못 찾고 둘 다 INSERT하면 한쪽이 유니크에 걸린다 — 그 트랜잭션은 이미 중단된
     * 상태라 안에서 복구할 수 없으므로, 트랜잭션 <b>밖</b>에서 잡아 다시 찾는다. 그래서 이 메서드는
     * {@code @Transactional}이 아니라 {@link TransactionTemplate}으로 경계를 직접 긋는다.
     * 재전송으로 돌려주는 경우엔 이벤트를 발행하지 않는다 — 첫 저장 때 이미 전파됐다.
     *
     * @return DB가 채운 값({@code messageId}, {@code roomSeq}, {@code sentAt})이 들어간 응답. 전파 페이로드와 같다
     */
    public ChatMessageResponseDto saveMessage(ChatMessageRequestDto dto) {
        Integer roomId = dto.getRoomId();
        UUID clientMsgId = dto.getClientMsgId();

        if (clientMsgId != null) {
            Optional<ChatMessages> existing = chatMessagesRepository.findByChatRoomsRoomIdAndClientMsgId(roomId, clientMsgId);
            if (existing.isPresent()) {
                log.info("재전송 - 기존 행을 돌려준다: room={}, clientMsgId={}", roomId, clientMsgId);
                return ChatMessageResponseDto.from(existing.get());
            }
        }

        try {
            return transactionTemplate.execute(tx -> insert(dto));
        } catch (DataIntegrityViolationException e) {
            if (clientMsgId == null) throw e;
            // 동시 재전송의 진 쪽. 이긴 쪽의 행이 이제는 보인다.
            return chatMessagesRepository.findByChatRoomsRoomIdAndClientMsgId(roomId, clientMsgId)
                    .map(ChatMessageResponseDto::from)
                    .orElseThrow(() -> e);
        }
    }

    /** 저장 트랜잭션의 본문. {@link #saveMessage}만 부른다. */
    private ChatMessageResponseDto insert(ChatMessageRequestDto dto) {
        ChatRooms room = chatRoomsRepository.findById(dto.getRoomId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

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

        // 3. 자기가 보낸 것은 읽은 것이다 — 방금 받은 번호까지. 안 올리면 내가 보낸 메시지가 내 안읽음에 잡힌다.
        chatJdbcRepository.markRead(dto.getRoomId(), dto.getSenderId(), roomSeq);
        log.info("### [3] 발신자 읽음 위치 {} (JDBC) 완료", roomSeq);

        // 4. 커밋되면 전파된다. 여기서 직접 보내면 롤백돼도 이미 나간 뒤다.
        ChatMessageResponseDto saved = ChatMessageResponseDto.from(msg);
        eventPublisher.publishEvent(new ChatMessageSaved(saved));
        return saved;
    }

    /**
     * 위로 스크롤 — {@code beforeSeq}보다 작은 것을 최신순으로.
     *
     * @param beforeSeq     새 커서. 첫 페이지면 null
     * @param lastMessageId 옛 커서. {@code beforeSeq}가 없을 때만 보고, 그 메시지의 {@code roomSeq}로 옮겨 쓴다.
     *                      응답에 {@code roomSeq}가 실리므로 클라이언트가 넘어오면 이 인자는 지운다
     */
    @Transactional(readOnly = true)
    public Slice<ChatMessageResponseDto> getChatHistory(Integer roomId, String userId, Long beforeSeq, Long lastMessageId, Pageable pageable) {
        ChatMembers memberInfo = chatMembersRepository.findByChatRoomsRoomIdAndMemberUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHATROOM_NOT_FOUND));

        Long cursor = beforeSeq;
        if (cursor == null && lastMessageId != null) {
            cursor = chatMessagesRepository.findRoomSeqByMessageId(lastMessageId).orElse(null);
        }

        // 정렬은 쿼리가 정한다(roomSeq DESC). 클라이언트의 sort 파라미터는 받지 않는다 — 커서와 정렬이
        // 어긋나면 페이지 경계에서 행이 겹치거나 빠진다.
        Pageable sizeOnly = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return chatMessagesRepository.findChatHistory(roomId, memberInfo.getJoinedAt(), cursor, sizeOnly)
                .map(ChatMessageResponseDto::from);
    }

    /**
     * 따라잡기 — 끊겼다 붙은 뒤 {@code afterSeq}보다 큰 것을 오래된 순으로 (ADR-0008 11-1, P2-12-5).
     * WebSocket은 끊긴 동안의 것을 다시 주지 않는다. 클라이언트는 마지막으로 받은 {@code roomSeq}를 넣고,
     * {@code hasNext}면 마지막 원소의 {@code roomSeq}로 이어 부른다.
     */
    @Transactional(readOnly = true)
    public Slice<ChatMessageResponseDto> catchUp(Integer roomId, String userId, long afterSeq, int limit) {
        ChatMembers memberInfo = chatMembersRepository.findByChatRoomsRoomIdAndMemberUserId(roomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHATROOM_AUTHOR));

        return chatMessagesRepository.findAfterSeq(roomId, memberInfo.getJoinedAt(), afterSeq, PageRequest.of(0, limit))
                .map(ChatMessageResponseDto::from);
    }

    /**
     * "여기까지 읽었다". 방 상세 조회의 부수효과였던 것을 명시적 호출로 바꿨다 — 이전에는 서버가
     * "지금 시각"으로 추측했고, 앱은 어디까지 봤는지 말할 수 없었다(P2-12-3).
     */
    @Transactional
    public void markRead(Integer roomId, String userId, long upToSeq) {
        int updated = chatJdbcRepository.markRead(roomId, userId, upToSeq);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CHATROOM_AUTHOR);
        }
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
