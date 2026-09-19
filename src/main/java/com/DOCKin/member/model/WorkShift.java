package com.DOCKin.member.model;

import java.time.Duration;
import java.time.LocalTime;

/**
 * 조선소 현장 3교대 (업무 규정 확정 전까지의 잠정값. 정책 테이블로 옮기는 것은 ADR-0005 "근무 정책 엔진", 백로그 P3).
 *
 * <p>{@code dayBoundary}는 <b>근무일이 바뀌는 벽시계 시각</b>이다(ADR-0010). 이 시각 이전의 이벤트는 전날 근무일에 속한다.
 * 주간·오후는 자정이라 근무일 = 날짜지만, 야간(22:00~06:00)은 자정을 걸치므로 정오를 경계로 둔다 —
 * 그래야 D일 22:00 출근과 D+1일 06:00 퇴근이 같은 근무일 D가 된다. 이 규칙이 없던 때는 야간조가 퇴근을 찍을 수 없었고(#98),
 * 00:30 출근이 22:00보다 "이른 시각"이라 지각이 아니었다. 계산은 {@code attendance.model.WorkDay}가 한다.
 *
 * <p>{@code endTime}은 시작 + 8시간. 지금은 조퇴 판정에 쓰지 않는다 — {@code AttendanceStatus} 하나에 지각과 조퇴를 함께
 * 담을 수 없어 스키마 결정이 먼저다(ADR-0010 5절). 자리만 둔다.
 */
public enum WorkShift {
    MORNING(LocalTime.of(6, 0), LocalTime.MIDNIGHT),
    AFTERNOON(LocalTime.of(14, 0), LocalTime.MIDNIGHT),
    NIGHT(LocalTime.of(22, 0), LocalTime.NOON);

    private static final Duration SHIFT_LENGTH = Duration.ofHours(8);

    private final LocalTime startTime;
    private final LocalTime dayBoundary;

    WorkShift(LocalTime startTime, LocalTime dayBoundary) {
        this.startTime = startTime;
        this.dayBoundary = dayBoundary;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    /** 이 시각 이전의 이벤트는 전날 근무일이다. */
    public LocalTime getDayBoundary() {
        return dayBoundary;
    }

    /** 시작 + 8시간. 야간은 06:00(다음날)이다 — {@link LocalTime}이라 날짜는 들어 있지 않다. */
    public LocalTime getEndTime() {
        return startTime.plus(SHIFT_LENGTH);
    }
}
