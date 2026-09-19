package com.DOCKin.checklist.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "checklist_items")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChecklistItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Integer itemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checklist_id", nullable = false)
    private Checklist checklist;

    @Column(name = "content", nullable = false, length = 255)
    private String content;

    @Column(name = "sequence", nullable = false)
    private Integer sequence;

    /**
     * 퇴역 시각(V15, ADR-0011 5절). 점검 기록이 있는 항목은 지우지도 문구를 바꾸지도 않는다 — 기록의 뜻이 바뀐다.
     * 대신 여기 시각을 찍는다. 이 시각 이후에 연 회차에는 나오지 않고, 그 전에 연 회차는 그대로 참조한다.
     * "문구를 고친다"는 퇴역 + 새 항목 추가다.
     */
    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    public boolean isRetired() {
        return retiredAt != null;
    }

    /** {@code at}에 연 회차에서 이 항목이 살아 있었는가. 퇴역 전이면 산 것이다. */
    public boolean isActiveAt(LocalDateTime at) {
        return retiredAt == null || retiredAt.isAfter(at);
    }

    public void retire(LocalDateTime now) {
        this.retiredAt = now;
    }
}
