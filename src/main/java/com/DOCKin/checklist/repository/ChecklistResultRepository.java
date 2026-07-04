package com.DOCKin.checklist.repository;

import com.DOCKin.checklist.model.ChecklistResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChecklistResultRepository extends JpaRepository<ChecklistResult, Integer> {

    // append-only 로그에서 체크리스트 하나의 항목별 "최신" 결과만 한 번의 쿼리로 가져온다 (N+1 방지).
    // 같은 초에 여러 이벤트가 몰려도 checked_at이 아니라 AUTO_INCREMENT PK(result_id)로 최신을 판정한다.
    @Query(value = """
            SELECT cr.* FROM checklist_results cr
            INNER JOIN (
                SELECT r.checklist_item_id AS item_id, MAX(r.result_id) AS max_result_id
                FROM checklist_results r
                INNER JOIN checklist_items ci ON r.checklist_item_id = ci.item_id
                WHERE ci.checklist_id = :checklistId
                GROUP BY r.checklist_item_id
            ) latest ON cr.result_id = latest.max_result_id
            """, nativeQuery = true)
    List<ChecklistResult> findLatestResultsByChecklistId(@Param("checklistId") Integer checklistId);

    boolean existsByChecklistItem_ItemId(Integer itemId);

    boolean existsByChecklistItem_Checklist_ChecklistId(Integer checklistId);
}
