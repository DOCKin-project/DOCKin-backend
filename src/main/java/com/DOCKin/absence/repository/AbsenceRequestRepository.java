package com.DOCKin.absence.repository;

import com.DOCKin.absence.model.AbsenceRequest;
import com.DOCKin.absence.model.AbsenceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

@Repository
public interface AbsenceRequestRepository extends JpaRepository<AbsenceRequest, Integer> {
    Page<AbsenceRequest> findByMember_UserIdOrderByRequestedAtDesc(String userId, Pageable pageable);
    Page<AbsenceRequest> findByStatusOrderByRequestedAtAsc(AbsenceStatus status, Pageable pageable);

    // 증빙서류 다운로드 권한(P2-18-7). documentUrl은 S3 URL 전체라 키로 끝나는지 본다.
    boolean existsByDocumentUrlEndingWith(String suffix);

    boolean existsByMember_UserIdAndDocumentUrlEndingWith(String userId, String suffix);

    /**
     * 상태를 바꿀 신청 행에 비관적 쓰기 락({@code SELECT ... FOR UPDATE})을 건다.
     *
     * <p>승인·거절·취소는 모두 "지금 PENDING(또는 APPROVED)인가"를 읽고 그에 따라 상태를 바꾼다.
     * 락 없이 읽으면 관리자 둘이 <b>같은 신청</b>을 동시에 승인할 때 둘 다 PENDING을 보고 통과하고,
     * 신청자 행 락({@code MemberRepository.findByUserIdForUpdate})에서 차례로 줄을 선 뒤 <b>연차를 두 번 깎는다.</b>
     * 신청자 행 락은 잔액 갱신을 직렬화할 뿐 "이 신청이 이미 처리됐는가"를 다시 보게 하지 않는다.
     * 승인 재검사({@link #existsOverlapping})는 자기 자신을 빼고, V11 EXCLUDE도 같은 행끼리는 걸리지 않는다.
     *
     * <p>락 순서는 언제나 <b>신청 행 → 신청자 행</b>이다. 승인과 취소가 같은 순서로 잡으므로 서로 교착하지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AbsenceRequest a WHERE a.id = :requestId")
    Optional<AbsenceRequest> findByIdForUpdate(@Param("requestId") Integer requestId);

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
