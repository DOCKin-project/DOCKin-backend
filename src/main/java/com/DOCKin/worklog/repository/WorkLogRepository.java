package com.DOCKin.worklog.repository;

import com.DOCKin.member.model.Member;
import com.DOCKin.worklog.model.WorkLog;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WorkLogRepository extends JpaRepository<WorkLog, Long> {
    @Transactional
    Page<WorkLog> findByMemberIn(List<Member> members, Pageable pageable);
    Page<WorkLog> findAllByMemberUserId(String targetUserId, Pageable pageable);

    @Query("SELECT w FROM WorkLog w WHERE w.title LIKE %:keyword% OR w.logText LIKE %:keyword%")
    Page<WorkLog> searchWorkLogs(@Param("keyword") String keyword, Pageable pageable);

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
