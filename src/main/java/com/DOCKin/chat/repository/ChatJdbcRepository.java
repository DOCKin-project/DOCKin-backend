package com.DOCKin.chat.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 메시지 저장에 따라오는 부수 갱신 두 개. <b>SQL을 SQL로 친다.</b>
 *
 * <h3>왜 JPA 리포지토리가 아닌가</h3>
 * {@code ChatService.saveMessage}는 메시지 한 건을 INSERT한 뒤 방과 멤버의 요약 컬럼
 * ({@code last_message_*}, {@code last_read_time})을 갱신한다. 이 갱신은 <b>엔티티를 거치지
 * 않는 것이 목적</b>이다 — {@code ChatRooms}를 로드해 setter로 고치면 더티 체킹이 방 행 전체를
 * UPDATE하고, 동시 발신자끼리 서로의 값을 덮어쓴다. 그래서 처음부터 네이티브 UPDATE였는데,
 * 그것을 {@code JpaRepository} 안에 {@code @Query(nativeQuery = true)}로 두면
 * "JPA 리포지토리인데 JPA가 하는 일이 없는" 메서드가 된다. 그런 것은 여기로 온다.
 * {@code ChatRoomsRepository}·{@code ChatMembersRepository}에는 엔티티를 오가는 것만 남는다.
 *
 * <h3>영속성 컨텍스트와의 관계</h3>
 * 같은 트랜잭션 안에서 {@code findById}로 올린 {@code ChatRooms}는 이 UPDATE 뒤에도
 * 옛 값을 들고 있다. 호출자가 그 객체의 요약 컬럼을 읽거나 고치면 안 된다 —
 * 이것은 네이티브 쿼리였을 때도 같았다.
 *
 * <h3>시각은 DB의 {@code NOW()}</h3>
 * {@code ChatMessages.sentAt}이 앱 시각이라 시계가 둘이다(백로그 P2-12-2). ADR-0008 D6이
 * {@code sent_at}을 DB 기본값으로 옮기기로 했으므로 이쪽은 그대로 둔다.
 */
@Repository
@RequiredArgsConstructor
public class ChatJdbcRepository {

    private final JdbcClient jdbcClient;

    /** 발신자 자신의 읽음 시각을 지금으로. 자기가 보낸 것은 읽은 것이다. */
    public void updateLastReadTime(Integer roomId, String userId) {
        jdbcClient.sql("UPDATE chat_members SET last_read_time = NOW() WHERE room_id = :roomId AND user_id = :userId")
                .param("roomId", roomId)
                .param("userId", userId)
                .update();
    }

    /**
     * 방 시퀀스를 발급하면서 마지막 메시지 요약을 함께 갱신한다. <b>INSERT보다 먼저 부른다.</b>
     *
     * <p>모든 발신자가 같은 행을 갱신하므로 동시 전송에서는 여기서 줄을 선다. 예전에는 그 줄이
     * 문제였다 — {@code message_id}는 줄을 서기 <b>전에</b> 받았으므로 락을 먼저 얻은 쪽이 먼저
     * 커밋되면 ID 순서와 커밋 순서가 어긋났고, 실측으로 커서가 56~78%를 놓쳤다
     * ({@code MessageIdCommitOrderMeasurementTest}). 이제 번호를 <b>그 줄 안에서</b> 받는다.
     * 락 순서 = 커밋 순서 = 번호 순서. 같은 테스트에서 유실 0이다(ADR-0008 5-4).
     *
     * <p>{@code last_message_*}에 가드가 필요 없는 것도 같은 이유다 — 락 안에서만 바뀌므로
     * 마지막에 쓴 것이 곧 마지막 메시지다(P2-12-4 해소).
     *
     * @return 이 메시지가 받은 {@code room_seq}. 호출자가 {@code chat_messages.room_seq}에 넣는다
     */
    public long nextRoomSeq(Integer roomId, String content) {
        return jdbcClient.sql("UPDATE chat_rooms "
                        + "SET last_message_seq = last_message_seq + 1, last_message_content = :content, last_message_at = NOW() "
                        + "WHERE room_id = :roomId RETURNING last_message_seq")
                .param("content", content)
                .param("roomId", roomId)
                .query(Long.class)
                .single();
    }
}
