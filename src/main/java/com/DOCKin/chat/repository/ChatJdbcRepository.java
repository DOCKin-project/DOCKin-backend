package com.DOCKin.chat.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.DOCKin.chat.dto.ChatRoomListRow;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * "여기까지 읽었다" (ADR-0008 11-1). 축은 {@code room_seq}다.
     *
     * <p>{@code GREATEST}라 멱등이다 — 같은 값이 두 번 와도, 늦게 도착한 작은 값이 와도 되돌아가지 않는다.
     * 발신자 본인도 이 메서드로 처리한다: 자기가 보낸 것은 읽은 것이므로 {@code saveMessage}가 방금 발급한
     * 번호로 부른다. {@code last_read_time}은 V7까지 병행해서 함께 올린다 — 읽는 곳은 이제 없다.
     *
     * @return 갱신된 행 수. 0이면 멤버가 아니다
     */
    public int markRead(Integer roomId, String userId, long upToSeq) {
        return jdbcClient.sql("UPDATE chat_members "
                        + "SET last_read_seq = GREATEST(last_read_seq, :seq), last_read_time = NOW() "
                        + "WHERE room_id = :roomId AND user_id = :userId")
                .param("seq", upToSeq)
                .param("roomId", roomId)
                .param("userId", userId)
                .update();
    }

    /**
     * 내 방 목록 한 페이지 — 조회 <b>한 번</b>. 안읽음은 {@code last_message_seq − last_read_seq}로 뺀다.
     *
     * <p>이전에는 JPA로 방을 받은 뒤 방마다 멤버 조회 + {@code chat_messages} COUNT를 했다(P2-12-1, 방 20개면
     * 쿼리 41개). 여기서는 {@code chat_messages}를 아예 읽지 않는다 — 메시지가 100만 건이어도 비용이 같다.
     *
     * <p>정렬은 {@code last_message_seq DESC}가 아니라 {@code last_message_at DESC}다. seq는 방 <b>안</b>의 번호라
     * 방끼리 비교하면 의미가 없다(메시지가 많은 방이 항상 위로 온다). 방 사이의 "최근"은 시각뿐이고,
     * 그 시각은 seq 발급과 같은 UPDATE·같은 락에서 DB {@code NOW()}로 찍히므로 경합이 없다(P2-12-4·8).
     * 메시지가 없는 방({@code NULL})은 맨 뒤, 동률은 {@code room_id}로 전순서를 만든다.
     */
    public List<ChatRoomListRow> roomsOf(String userId, int limit, long offset) {
        return jdbcClient.sql("SELECT r.room_id, r.room_name, r.creator_id, r.created_at, "
                        + "       r.last_message_content, r.last_message_at, r.last_message_seq, m.last_read_seq "
                        + "  FROM chat_members m JOIN chat_rooms r ON r.room_id = m.room_id "
                        + " WHERE m.user_id = :userId "
                        + " ORDER BY r.last_message_at DESC NULLS LAST, r.room_id DESC "
                        + " LIMIT :limit OFFSET :offset")
                .param("userId", userId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, i) -> new ChatRoomListRow(
                        rs.getInt("room_id"),
                        rs.getString("room_name"),
                        rs.getString("creator_id"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getString("last_message_content"),
                        rs.getObject("last_message_at", LocalDateTime.class),
                        rs.getLong("last_message_seq"),
                        rs.getLong("last_read_seq")))
                .list();
    }

    public long countRoomsOf(String userId) {
        return jdbcClient.sql("SELECT count(*) FROM chat_members WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /**
     * 여러 방의 멤버를 한 번에. 방마다 {@code getMembers()}로 LAZY 로딩하면 페이지의 방 수만큼 쿼리가 나간다 —
     * 목록을 한 번으로 줄여 놓고 참가자에서 다시 N+1을 만들 이유가 없다.
     */
    public Map<Integer, List<String>> participantsOf(Collection<Integer> roomIds) {
        if (roomIds.isEmpty()) return Map.of();
        Map<Integer, List<String>> out = new HashMap<>();
        jdbcClient.sql("SELECT room_id, user_id FROM chat_members WHERE room_id IN (:ids) ORDER BY id")
                .param("ids", roomIds)
                .query((rs, i) -> Map.entry(rs.getInt("room_id"), rs.getString("user_id")))
                .list()
                .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return out;
    }

    /** 한 방에서의 내 읽음 위치와 방의 현재 번호. 상세 조회의 안읽음 계산용. */
    public Optional<long[]> seqPairOf(Integer roomId, String userId) {
        return jdbcClient.sql("SELECT r.last_message_seq, m.last_read_seq "
                        + "  FROM chat_members m JOIN chat_rooms r ON r.room_id = m.room_id "
                        + " WHERE m.room_id = :roomId AND m.user_id = :userId")
                .param("roomId", roomId)
                .param("userId", userId)
                .query((rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)})
                .optional();
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
    /**
     * 방 멤버의 {@code user_id}만. 전파 팬아웃용이다({@code ChatBroadcaster}).
     * 엔티티를 올려 {@code getMember().getUserId()}로 역참조하면 멤버 수만큼 쿼리가 더 나간다 — 그래서 SQL이다.
     */
    public List<String> memberIds(Integer roomId) {
        return jdbcClient.sql("SELECT user_id FROM chat_members WHERE room_id = :roomId")
                .param("roomId", roomId)
                .query(String.class)
                .list();
    }

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
