package com.DOCKin.checklist.model;

import com.DOCKin.member.model.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 점검 회차 — 한 사람이 한 템플릿을 한 번 수행한 <b>사건</b> (ADR-0011).
 *
 * <p>이 테이블이 없던 때는 {@code checklist_results}가 (항목, 사용자, 체크, 시각)만 들고 있어 "현재 상태"가
 * 템플릿 전역이었다 — A가 월요일에 체크한 작업 전 점검이 화요일 B에게 체크된 채로 보였고, "이 작업 전에 점검을
 * 했는가"에 답할 수 없었다. 점검은 템플릿의 상태가 아니라 사건이고, 결과는 그 사건에 속한다.
 *
 * <p>상태는 셋이고 컬럼은 {@code closed_at}·{@code outcome} 둘이다. 열려 있으면(둘 다 null) IN_PROGRESS, 닫혔으면
 * {@code outcome}이 곧 상태다. 부분 유니크 {@code uq_checklist_runs_open}(checklist_id, user_id WHERE closed_at IS NULL)이
 * "한 사람은 같은 템플릿의 열린 회차를 하나만 가진다"를 DB에서 지킨다 — 더블탭과 재접속이 같은 회차로 돌아온다.
 *
 * <p>배치는 없다. {@link #OPEN_SPAN}을 넘긴 열린 회차는 그 사람이 같은 템플릿을 <b>다시 열 때</b> ABANDONED로 닫힌다.
 * 그 전까지는 열려 있고 체크·완료도 된다 — 12시간은 "새로 시작할 때 이어서 할지 새로 할지"의 기준이지 잠금이 아니다.
 */
@Entity
@Table(name = "checklist_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChecklistRun {

    /** 이보다 오래된 열린 회차는 이어서 하지 않고 새로 연다. 8시간 교대 + 잔업을 덮는 값. */
    public static final Duration OPEN_SPAN = Duration.ofHours(12);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "run_id")
    private Long runId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checklist_id", nullable = false)
    private Checklist checklist;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Member member;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20)
    private ChecklistRunOutcome outcome;

    @Builder
    public ChecklistRun(Checklist checklist, Member member, LocalDateTime startedAt) {
        this.checklist = checklist;
        this.member = member;
        this.startedAt = startedAt;
    }

    public boolean isOpen() {
        return closedAt == null;
    }

    /** 열린 지 {@link #OPEN_SPAN}이 지났는가. 열린 회차에만 뜻이 있다. */
    public boolean isStale(LocalDateTime now) {
        return isOpen() && Duration.between(startedAt, now).compareTo(OPEN_SPAN) > 0;
    }

    public boolean isOwnedBy(String userId) {
        return member.getUserId().equals(userId);
    }

    /** 응답용 상태. 컬럼이 아니라 파생이다 — 열려 있으면 IN_PROGRESS, 닫혔으면 결말. */
    public String status() {
        return isOpen() ? "IN_PROGRESS" : outcome.name();
    }

    public void complete(LocalDateTime now) {
        close(now, ChecklistRunOutcome.COMPLETED);
    }

    public void abandon(LocalDateTime now) {
        close(now, ChecklistRunOutcome.ABANDONED);
    }

    private void close(LocalDateTime now, ChecklistRunOutcome outcome) {
        if (!isOpen()) {
            throw new IllegalStateException("이미 닫힌 회차: " + runId);
        }
        this.closedAt = now;
        this.outcome = outcome;
    }
}
