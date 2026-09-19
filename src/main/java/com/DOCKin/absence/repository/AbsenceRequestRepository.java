package com.DOCKin.absence.repository;

import com.DOCKin.absence.model.AbsenceRequest;
import com.DOCKin.absence.model.AbsenceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;

@Repository
public interface AbsenceRequestRepository extends JpaRepository<AbsenceRequest, Integer> {
    Page<AbsenceRequest> findByMember_UserIdOrderByRequestedAtDesc(String userId, Pageable pageable);
    Page<AbsenceRequest> findByStatusOrderByRequestedAtAsc(AbsenceStatus status, Pageable pageable);

    // 증빙서류 다운로드 권한(P2-18-7). documentUrl은 S3 URL 전체라 키로 끝나는지 본다.
    boolean existsByDocumentUrlEndingWith(String suffix);

    boolean existsByMember_UserIdAndDocumentUrlEndingWith(String userId, String suffix);

    /**
     * 같은 사용자의 기간이 겹치는 신청이 있는가 (#104). 겹침은 {@code start <= other.end AND end >= other.start}.
     * {@code excludeId}는 승인 재검사에서 자기 자신을 빼려고 — 신청 시에는 아직 id가 없으니 0을 준다.
     * APPROVED끼리는 V11의 EXCLUDE 제약이 DB에서 한 번 더 막는다.
     */
    @Query("""
            SELECT count(a) > 0 FROM AbsenceRequest a
            WHERE a.member.userId = :userId
              AND a.status IN :statuses
              AND a.startDate <= :endDate
              AND a.endDate >= :startDate
              AND a.id <> :excludeId
            """)
    boolean existsOverlapping(@Param("userId") String userId,
                              @Param("startDate") LocalDate startDate,
                              @Param("endDate") LocalDate endDate,
                              @Param("statuses") Collection<AbsenceStatus> statuses,
                              @Param("excludeId") Integer excludeId);
}
