package com.DOCKin.checklist.model;

import com.DOCKin.member.model.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

// 항목 단위 점검 기록. 체크/해제할 때마다 새 행을 INSERT하는 append-only 로그이며,
// "현재 상태"는 **회차 안에서** 항목별 최신 행(MAX(resultId))으로 조회한다. 그래서 값 변경(Setter)을 두지 않는다.
// 회차(run)가 없던 때는 이 최신 판정이 템플릿 전역이라 남의 점검이 내 화면에 체크된 채로 보였다(ADR-0011).
@Entity
@Table(name = "checklist_results")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChecklistResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Integer resultId;

    /** 이 결과가 속한 회차(V11). 결과는 사건에 속한다 — 템플릿에 속하지 않는다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private ChecklistRun run;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checklist_item_id", nullable = false)
    private ChecklistItem checklistItem;

    /** 점검자. 회차의 점검자와 같다 — 서비스가 보장한다. 감사 로그로서 행 단독으로 읽히게 남겨 둔다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Member member;

    @Column(name = "is_checked", nullable = false)
    private Boolean isChecked;

    @CreationTimestamp
    @Column(name = "checked_at", updatable = false, nullable = false)
    private LocalDateTime checkedAt;
}
