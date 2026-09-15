package com.DOCKin.worklog.repository;

import com.DOCKin.member.model.Member;
import com.DOCKin.worklog.model.WorkLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WorkLogRepository extends JpaRepository<WorkLog, Long> {

    /*
     * 세 목록 쿼리는 같은 꼴이다 (DB-IMPROVEMENT-PLAN A4·D2, 2026-09-15).
     *
     *   - Slice: COUNT를 내지 않는다. 100만 행에서 COUNT는 인덱스가 있어도 매 요청 12.8ms였고
     *     (P2-15-5 ②), MVCC라 LIMIT의 이득이 없다 — 보이는 행인지 전부 확인해야 센다.
     *     Slice는 size+1을 읽어 hasNext만 답한다. 채팅(findChatHistory)과 같은 선택이다.
     *   - 커서 (beforeCreatedAt, beforeLogId): OFFSET은 앞의 행을 읽고 버린다 — 500페이지가
     *     150ms였다(P2-15-5 ③). 커서는 마지막으로 본 행 "다음"부터 인덱스로 바로 간다.
     *     둘 다 null이면 첫 페이지(OFFSET 0)다. 정렬 키 (created_at, log_id)는 PK가 뒤에
     *     붙어 전순서라 경계에서 행이 겹치거나 빠지지 않는다 — findForIndexingAfter와 같은 판단.
     *   - ORDER BY는 쿼리가 정한다. 클라이언트 sort를 붙이면 커서와 어긋난다.
     *
     * 커서 조건을 (createdAt, logId) < (:c, :id) 행 비교로 쓰지 않는 것은 JPQL이 그 문법을
     * 모르기 때문이다. 풀어 쓴 아래 형태를 PostgreSQL 플래너는 같은 뜻으로 읽는다.
     *
     * CAST(:beforeCreatedAt AS Timestamp)가 붙은 이유: 커서가 null이면 Hibernate가 `? IS NULL`의
     * ?를 타입 없이 보내고, PostgreSQL은 "could not determine data type of parameter"로 거부한다
     * (2026-09-15 실측). 채팅의 :beforeSeq(Long)는 같은 꼴로도 통과했는데 timestamp는 안 됐다 —
     * JPA가 DB를 가려 주는 데도 구멍이 있다. MySQL은 타입 없는 null을 받아 주므로 거기선 안 드러난다.
     */

    @Query("""
            SELECT w FROM WorkLog w
            WHERE w.member IN :members
              AND (CAST(:beforeCreatedAt AS Timestamp) IS NULL
                   OR w.createdAt < :beforeCreatedAt
                   OR (w.createdAt = :beforeCreatedAt AND w.logId < :beforeLogId))
            ORDER BY w.createdAt DESC, w.logId DESC
            """)
    Slice<WorkLog> findByMemberIn(@Param("members") List<Member> members,
                                  @Param("beforeCreatedAt") LocalDateTime beforeCreatedAt,
                                  @Param("beforeLogId") Long beforeLogId,
                                  Pageable pageable);

    @Query("""
            SELECT w FROM WorkLog w
            WHERE w.member.userId = :targetUserId
              AND (CAST(:beforeCreatedAt AS Timestamp) IS NULL
                   OR w.createdAt < :beforeCreatedAt
                   OR (w.createdAt = :beforeCreatedAt AND w.logId < :beforeLogId))
            ORDER BY w.createdAt DESC, w.logId DESC
            """)
    Slice<WorkLog> findAllByMemberUserId(@Param("targetUserId") String targetUserId,
                                         @Param("beforeCreatedAt") LocalDateTime beforeCreatedAt,
                                         @Param("beforeLogId") Long beforeLogId,
                                         Pageable pageable);

    /**
     * 키워드 검색. <b>같은 구역의 작업일지만.</b>
     *
     * <p>목록({@code findByMemberIn})과 타인 조회({@code readOtherWorklog})는 구역으로 가리는데
     * 검색만 전체를 뒤졌다(백로그 P2-18-10). 검색이 목록보다 넓게 보이면 안 된다.
     *
     * <p>{@code LIKE %kw%}는 양쪽 와일드카드라 B-tree를 못 탄다(P2-15-5 ⑤). {@code pg_trgm}은
     * 측정 뒤 결정한다(DB-IMPROVEMENT-PLAN D1).
     */
    @Query("""
            SELECT w FROM WorkLog w
            WHERE w.member IN :members
              AND (w.title LIKE %:keyword% OR w.logText LIKE %:keyword%)
              AND (CAST(:beforeCreatedAt AS Timestamp) IS NULL
                   OR w.createdAt < :beforeCreatedAt
                   OR (w.createdAt = :beforeCreatedAt AND w.logId < :beforeLogId))
            ORDER BY w.createdAt DESC, w.logId DESC
            """)
    Slice<WorkLog> searchWorkLogs(@Param("keyword") String keyword,
                                  @Param("members") List<Member> members,
                                  @Param("beforeCreatedAt") LocalDateTime beforeCreatedAt,
                                  @Param("beforeLogId") Long beforeLogId,
                                  Pageable pageable);

    /**
     * 작업일지 사진 다운로드 권한(P2-18-7). 그 사진이 붙은 작업일지가 요청자와 같은 구역이면 된다 —
     * 목록·검색이 보여주는 범위와 같다. imageUrl은 S3 URL 전체라 키로 끝나는지 본다.
     */
    @Query("""
            SELECT CASE WHEN COUNT(i) > 0 THEN true ELSE false END
            FROM WorkLogImage i
            WHERE i.imageUrl LIKE CONCAT('%/', :objectKey)
              AND i.workLog.member.shipYardArea = :shipYardArea
            """)
    boolean imageVisibleFromArea(@Param("objectKey") String objectKey, @Param("shipYardArea") String shipYardArea);

    /**
     * RAG 인덱싱 배치 전용 조회.
     *
     * <p>{@code member}를 페치 조인하는 이유는 둘이다.
     * <ul>
     *   <li>배치는 웹 요청이 아니라 OSIV가 걸리지 않으므로, 트랜잭션 밖에서 {@code getMember()}에
     *       접근하면 {@code LazyInitializationException}이 발생한다.</li>
     *   <li>지연 로딩으로 두면 작업일지마다 쿼리가 하나씩 더 나가 N+1이 된다.</li>
     * </ul>
     * {@code member}는 {@code @ManyToOne}이라 페치 조인과 페이징을 함께 써도
     * 메모리 페이징으로 떨어지지 않는다.
     *
     * <p><b>OFFSET이 아니라 커서(keyset) 방식이다.</b> {@code LIMIT/OFFSET}은 앞의 행을 읽고 버리므로
     * 뒤 페이지로 갈수록 느려지고(1,000페이지 순회 시 누적 스킵 약 5천만 행), 정렬이 불안정하면
     * 페이지 경계에서 행이 중복되거나 누락된다. 인덱싱이 도는 동안 새 작업일지가 INSERT되면
     * 뒤 페이지가 밀려 건너뛰는 행도 생긴다.
     * {@code logId > :lastId}는 PK 인덱스로 시작점을 바로 찾으므로 페이지 위치와 무관하게 비용이 일정하고,
     * 정렬 키가 유니크(PK)라 중복·누락도 발생하지 않는다.
     */
    @Query("""
            SELECT w FROM WorkLog w
            LEFT JOIN FETCH w.member
            WHERE w.logId > :lastId
            ORDER BY w.logId ASC
            """)
    List<WorkLog> findForIndexingAfter(@Param("lastId") Long lastId, Pageable pageable);
}
