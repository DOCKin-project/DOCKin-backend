package com.DOCKin.checklist.repository;

import com.DOCKin.checklist.model.ChecklistRun;
import com.DOCKin.checklist.model.ChecklistRunOutcome;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ChecklistRunRepository extends JpaRepository<ChecklistRun, Long> {

    /**
     * 이 사람의 이 템플릿 열린 회차. 부분 유니크 {@code uq_checklist_runs_open}이 최대 하나임을 보장하므로
     * {@code findFirst}는 안전장치일 뿐이다.
     */
    Optional<ChecklistRun> findFirstByChecklist_ChecklistIdAndMember_UserIdAndClosedAtIsNull(Integer checklistId, String userId);

    /** 내 회차 목록 — 기간으로. {@code (user_id, started_at DESC)} 인덱스를 탄다. */
    Slice<ChecklistRun> findByMember_UserIdAndStartedAtBetweenOrderByStartedAtDesc(
            String userId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    /**
     * 관리자 목록 — 하루치 회차를 장비·점검자·상태로 거른다. "오늘 크레인 3호 작업 전 점검 누가 했나".
     *
     * <p>세 필터는 전부 선택이다. null 바인딩은 {@code AttendanceRepository.summarizeByAreaAndDate}와 같은 이유로
     * {@code CAST(... AS ...) IS NULL}. 상태는 열림(closed_at IS NULL)과 결말(outcome) 두 축이라 파라미터가 둘이다 —
     * {@code openOnly=true}면 열린 것만, {@code outcome}이 있으면 그 결말만, 둘 다 없으면 전부.
     *
     * <p>{@code JOIN FETCH} 셋은 응답 DTO가 장비 번호·제목·점검자를 읽기 때문이다 — 없으면 행마다 셋씩 나간다.
     * 컬렉션이 아니라 {@code Slice}와 함께 써도 메모리 페이징이 아니다. 인덱스는 {@code (checklist_id, started_at DESC)} /
     * {@code (user_id, started_at DESC)} — 필터가 장비·점검자일 때 각각 탄다.
     */
    @Query("""
            SELECT r FROM ChecklistRun r
            JOIN FETCH r.checklist c
            JOIN FETCH c.equipment e
            JOIN FETCH r.member m
            WHERE r.startedAt >= :from AND r.startedAt < :to
              AND (CAST(:equipmentId AS Long) IS NULL OR e.equipmentId = :equipmentId)
              AND (CAST(:userId AS String) IS NULL OR m.userId = :userId)
              AND (:openOnly = false OR r.closedAt IS NULL)
              AND (CAST(:outcome AS String) IS NULL OR r.outcome = :outcome)
            ORDER BY r.startedAt DESC, r.runId DESC
            """)
    Slice<ChecklistRun> findForAdmin(@Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to,
                                     @Param("equipmentId") Long equipmentId,
                                     @Param("userId") String userId,
                                     @Param("openOnly") boolean openOnly,
                                     @Param("outcome") ChecklistRunOutcome outcome,
                                     Pageable pageable);

    /** 템플릿 삭제 가드. 회차가 하나라도 있으면 그 템플릿은 기록의 일부다. */
    boolean existsByChecklist_ChecklistId(Integer checklistId);
}
