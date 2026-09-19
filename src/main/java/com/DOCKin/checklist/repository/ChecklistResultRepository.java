package com.DOCKin.checklist.repository;

import com.DOCKin.checklist.model.ChecklistResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChecklistResultRepository extends JpaRepository<ChecklistResult, Integer> {

    // append-only 로그에서 회차 하나의 항목별 "최신" 결과만 한 번의 쿼리로 가져온다 (N+1 방지).
    // 같은 초에 여러 이벤트가 몰려도 checked_at이 아니라 IDENTITY PK(resultId)로 최신을 판정한다.
    //
    // 범위는 회차다. 예전 findLatestResultsByChecklistId는 템플릿 전역이라 다른 사람·다른 날의 체크가
    // 섞였다(ADR-0011). (run_id, checklist_item_id, result_id DESC) 인덱스가 이 GROUP BY를 받는다.
    //
    // 결과가 관리 대상 엔티티(ChecklistResult)이므로 JPQL로 쓴다. 이전에는 같은 뜻의 SQL을
    // nativeQuery로 두었는데, 엔티티를 돌려주는 조회를 SQL로 쓰면 컬럼 이름이 엔티티 매핑과
    // 따로 놀아 스키마가 바뀔 때 ddl-auto=validate가 잡아주지 못한다.
    @Query("""
            SELECT cr FROM ChecklistResult cr
            WHERE cr.resultId IN (
                SELECT MAX(r.resultId) FROM ChecklistResult r
                WHERE r.run.runId = :runId
                GROUP BY r.checklistItem.itemId)
            """)
    List<ChecklistResult> findLatestResultsByRunId(@Param("runId") Long runId);

    boolean existsByChecklistItem_ItemId(Integer itemId);

    boolean existsByChecklistItem_Checklist_ChecklistId(Integer checklistId);
}
