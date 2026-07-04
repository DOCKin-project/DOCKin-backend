package com.DOCKin.checklist.model;

import com.DOCKin.member.model.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

// 항목 단위 점검 기록. 체크/해제할 때마다 새 행을 INSERT하는 append-only 로그이며,
// "현재 상태"는 항목별 최신 행(MAX(resultId))으로 조회한다. 그래서 값 변경(Setter)을 두지 않는다.
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checklist_item_id", nullable = false)
    private ChecklistItem checklistItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Member member;

    @Column(name = "is_checked", nullable = false)
    private Boolean isChecked;

    @CreationTimestamp
    @Column(name = "checked_at", updatable = false, nullable = false)
    private LocalDateTime checkedAt;
}
