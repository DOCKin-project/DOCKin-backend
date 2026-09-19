package com.DOCKin.absence.model;

import com.DOCKin.member.model.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "absence_requests")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbsenceRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Integer requestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", length = 20, nullable = false)
    private AbsenceType type;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "document_url")
    private String documentUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private AbsenceStatus status = AbsenceStatus.PENDING;

    @CreationTimestamp
    @Column(name = "requested_at", updatable = false)
    private LocalDateTime requestedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processed_by")
    private Member processedBy;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    // 승인/거절/취소 사유 코멘트
    @Column(name = "decision_comment")
    private String decisionComment;

    /**
     * 승인 때 실제로 깎은 연차 일수(V17). 취소 때 <b>이 값을</b> 돌려준다 — 그 사이 캘린더가 바뀌어 근무일 수를
     * 다시 세면 다른 값이 나올 수 있는데, 환급은 재계산이 아니라 기록이어야 한다. 병가·V17 이전 승인은 null.
     */
    @Column(name = "deducted_days")
    private Integer deductedDays;
}
