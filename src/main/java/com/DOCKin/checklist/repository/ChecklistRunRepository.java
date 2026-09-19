package com.DOCKin.checklist.repository;

import com.DOCKin.checklist.model.ChecklistRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
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

    /** 템플릿 삭제 가드. 회차가 하나라도 있으면 그 템플릿은 기록의 일부다. */
    boolean existsByChecklist_ChecklistId(Integer checklistId);
}
