package com.DOCKin.worklog.repository;

import com.DOCKin.worklog.model.WorkLog;
import com.DOCKin.worklog.model.WorkLogStatus;
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
     * 모르기 때문이다. 풀어 쓰는 형태는 둘인데 뜻은 같아도 실행계획이 다르다 — findByArea 주석(#118).
     *
     * CAST(:beforeCreatedAt AS Timestamp)가 붙은 이유: 커서가 null이면 Hibernate가 `? IS NULL`의
     * ?를 타입 없이 보내고, PostgreSQL은 "could not determine data type of parameter"로 거부한다
     * (2026-09-15 실측). 채팅의 :beforeSeq(Long)는 같은 꼴로도 통과했는데 timestamp는 안 됐다 —
     * JPA가 DB를 가려 주는 데도 구멍이 있다. MySQL은 타입 없는 null을 받아 주므로 거기선 안 드러난다.
     */

    /**
     * 같은 구역의 작업일지 목록. {@code status}는 선택 필터다(P2-17-1). null이면 전부. 관리자의 미승인 큐와
     * 근로자의 "내 반려 건"이 같은 쿼리를 쓴다. {@code CAST(:status AS String)}은 {@code beforeCreatedAt}과
     * 같은 이유 — null이면 Hibernate가 타입 없는 ?를 보내고 PostgreSQL이 거부한다. enum은 STRING으로
     * 매핑되므로 String으로 캐스팅한다.
     *
     * <p><b>구역은 조인으로 거른다, 사용자 목록으로 거르지 않는다 (#118).</b> 2026-09-19까지는 서비스가
     * {@code findByShipYardArea}로 구역 사용자를 전부 엔티티로 올린 뒤 {@code w.member IN :members}에
     * 넣었다. 쿼리 개수로는 1이라 안 보였는데(QueryCountTest), 크기로는 구역 1.3만 명이면 바인드 1.3만 개에
     * 15ms짜리 사용자 조회와 엔티티 1.3만 개 하이드레이션이 요청마다 붙었다 — AWS 밤 15 k6 부하에서
     * {@code pg_stat_statements} 1위. {@code w.member.shipYardArea}는 users를 PK로 조인한다.
     *
     * <p>커서 조건이 {@code created_at <= :c AND (created_at < :c OR log_id < :id)}인 이유: 위 클래스
     * 주석의 OR 풀어쓰기는 뜻은 같지만 플래너가 인덱스 범위 조건(Index Cond)으로 못 쓰고 필터로만 써서,
     * V10 인덱스가 있어도 중간 페이지는 인덱스를 처음부터 걷는다(실측 367ms). {@code <=}를 앞에 두면
     * 그것이 Index Cond가 되고 나머지가 필터다(3.5ms). 첫 페이지(커서 null)는 {@code IS NULL}로 통과한다.
     */
    @Query("""
            SELECT w FROM WorkLog w
            WHERE w.member.shipYardArea = :shipYardArea
              AND (CAST(:status AS String) IS NULL OR w.status = :status)
              AND (CAST(:beforeCreatedAt AS Timestamp) IS NULL
                   OR (w.createdAt <= :beforeCreatedAt
                       AND (w.createdAt < :beforeCreatedAt OR w.logId < :beforeLogId)))
            ORDER BY w.createdAt DESC, w.logId DESC
            """)
    Slice<WorkLog> findByArea(@Param("shipYardArea") String shipYardArea,
                              @Param("status") WorkLogStatus status,
                              @Param("beforeCreatedAt") LocalDateTime beforeCreatedAt,
                              @Param("beforeLogId") Long beforeLogId,
                              Pageable pageable);

    @Query("""
            SELECT w FROM WorkLog w
            WHERE w.member.userId = :targetUserId
              AND (CAST(:beforeCreatedAt AS Timestamp) IS NULL
                   OR (w.createdAt <= :beforeCreatedAt
                       AND (w.createdAt < :beforeCreatedAt OR w.logId < :beforeLogId)))
            ORDER BY w.createdAt DESC, w.logId DESC
            """)
    Slice<WorkLog> findAllByMemberUserId(@Param("targetUserId") String targetUserId,
                                         @Param("beforeCreatedAt") LocalDateTime beforeCreatedAt,
                                         @Param("beforeLogId") Long beforeLogId,
                                         Pageable pageable);

    /**
     * 키워드 검색. <b>같은 구역의 작업일지만.</b>
     *
     * <p>목록({@code findByArea})과 타인 조회({@code readOtherWorklog})는 구역으로 가리는데
     * 검색만 전체를 뒤졌다(백로그 P2-18-10). 검색이 목록보다 넓게 보이면 안 된다. 구역 필터와
     * 커서 형태는 {@code findByArea}와 같은 이유로 같은 꼴이다(#118).
     *
     * <p>{@code LIKE %kw%}는 양쪽 와일드카드라 B-tree를 못 탄다(P2-15-5 ⑤). {@code pg_trgm}은
     * 측정 뒤 결정한다(DB-IMPROVEMENT-PLAN D1).
     */
    @Query("""
            SELECT w FROM WorkLog w
            WHERE w.member.shipYardArea = :shipYardArea
              AND (w.title LIKE %:keyword% OR w.logText LIKE %:keyword%)
              AND (CAST(:beforeCreatedAt AS Timestamp) IS NULL
                   OR (w.createdAt <= :beforeCreatedAt
                       AND (w.createdAt < :beforeCreatedAt OR w.logId < :beforeLogId)))
            ORDER BY w.createdAt DESC, w.logId DESC
            """)
    Slice<WorkLog> searchWorkLogs(@Param("keyword") String keyword,
                                  @Param("shipYardArea") String shipYardArea,
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
