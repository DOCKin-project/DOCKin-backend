package com.DOCKin.checklist.repository;

import com.DOCKin.checklist.model.ChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, Integer> {
    /** 전 항목 — 퇴역 포함. 관리자 템플릿 화면과 템플릿 삭제가 쓴다. */
    List<ChecklistItem> findByChecklist_ChecklistIdOrderBySequenceAscItemIdAsc(Integer checklistId);

    /**
     * {@code at}에 살아 있던 항목 — 회차가 보는 목록. 회차의 {@code started_at}을 넣으면 그 회차가 열릴 때의 템플릿이고,
     * 지금 시각을 넣으면 새 회차가 볼 템플릿이다. 퇴역 전에 연 회차는 퇴역한 항목을 계속 본다(ADR-0011 5절).
     */
    @Query("""
            SELECT i FROM ChecklistItem i
            WHERE i.checklist.checklistId = :checklistId
              AND (i.retiredAt IS NULL OR i.retiredAt > :at)
            ORDER BY i.sequence ASC, i.itemId ASC
            """)
    List<ChecklistItem> findActiveAt(@Param("checklistId") Integer checklistId, @Param("at") LocalDateTime at);
}
