package com.DOCKin.chat.repository;

import com.DOCKin.chat.model.ChatMessages;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ChatMessagesRepository extends JpaRepository<ChatMessages, Long> {

    /** 재전송 멱등(ADR-0008 D9). 같은 방·같은 클라이언트 키는 한 행이다 — {@code UNIQUE(room_id, client_msg_id)}. */
    Optional<ChatMessages> findByChatRoomsRoomIdAndClientMsgId(Integer roomId, UUID clientMsgId);


    /**
     * 1. 내역 조회 — 위로 스크롤. {@code beforeSeq}보다 <b>작은</b> 것을 최신순으로.
     *
     * <p>축이 {@code messageId}에서 {@code roomSeq}로 바뀌었다(ADR-0008 D7, V6). {@code IDENTITY}는 커밋 순서를
     * 보장하지 않아 커서로 쓰면 뒤늦게 커밋된 작은 ID를 건너뛴다 — {@code MessageIdCommitOrderMeasurementTest}.
     * 입장 전 메시지는 보이지 않는다({@code sentAt > joinedAt}) — 이것은 순서가 아니라 가시성 규칙이라 그대로다.
     */
    @Query("SELECT m FROM ChatMessages m " +
            "WHERE m.chatRooms.roomId = :roomId " +
            "AND m.sentAt > :joinedAt " +
            "AND (:beforeSeq IS NULL OR m.roomSeq < :beforeSeq) " +
            "ORDER BY m.roomSeq DESC")
    Slice<ChatMessages> findChatHistory(
            @Param("roomId") Integer roomId,
            @Param("joinedAt") LocalDateTime joinedAt,
            @Param("beforeSeq") Long beforeSeq,
            Pageable pageable);

    /**
     * 1-1. 따라잡기 — 끊겼다 붙은 뒤. {@code afterSeq}보다 <b>큰</b> 것을 오래된 순으로 (ADR-0008 11-1).
     *
     * <p>내역 조회와 방향이 반대다. 클라이언트는 마지막으로 받은 {@code roomSeq}를 넣고, {@code hasNext}면 이어 부른다.
     * {@code (room_id, room_seq)} 유니크 인덱스를 타므로 O(log n + k)다.
     */
    @Query("SELECT m FROM ChatMessages m " +
            "WHERE m.chatRooms.roomId = :roomId " +
            "AND m.sentAt > :joinedAt " +
            "AND m.roomSeq > :afterSeq " +
            "ORDER BY m.roomSeq ASC")
    Slice<ChatMessages> findAfterSeq(
            @Param("roomId") Integer roomId,
            @Param("joinedAt") LocalDateTime joinedAt,
            @Param("afterSeq") long afterSeq,
            Pageable pageable);

    /** 옛 커서({@code lastMessageId})를 새 축으로 옮길 때 한 번 쓴다. */
    @Query("SELECT m.roomSeq FROM ChatMessages m WHERE m.messageId = :messageId")
    Optional<Long> findRoomSeqByMessageId(@Param("messageId") Long messageId);

    // 2. 키워드 검색
    @Query("SELECT m FROM ChatMessages m " +
            "WHERE m.chatRooms.roomId = :roomId " +
            "AND m.sentAt > :joinedAt " +
            "AND m.content LIKE %:keyword% " +
            "ORDER BY m.roomSeq DESC")
    Slice<ChatMessages> searchMessageByKeyword(
            @Param("roomId") Integer roomId,
            @Param("joinedAt") LocalDateTime joinedAt,
            @Param("keyword") String keyword,
            Pageable pageable);

    // 안읽음 COUNT는 없앴다. last_message_seq − last_read_seq로 뺀다(ChatJdbcRepository.roomsOf).
}