package com.DOCKin.attendance.model;

import com.DOCKin.member.model.WorkShift;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 근무일 규칙 (ADR-0010). 출근·퇴근·결근 배치가 "이 시각은 어느 근무일인가"를 물을 때 전부 여기로 온다.
 *
 * <p><b>근무일은 벽시계 날짜가 아니다.</b> {@code work_date = LocalDate.now()}였을 때 야간조(22:00~06:00)는
 * 출근 행이 D일에, 퇴근 조회가 D+1일에 걸려 퇴근을 찍을 수 없었다(#98). 규칙은 한 줄이다 —
 * 교대의 {@link WorkShift#getDayBoundary() dayBoundary} 이전이면 전날, 아니면 그날. 주간·오후는 경계가 자정이라
 * 예전과 같고, 야간만 정오를 경계로 하여 22:00 출근과 다음날 06:00 퇴근(잔업이면 11:00까지)이 한 근무일에 묶인다.
 *
 * <p>Spring도 DB도 모르는 순수 함수다 — {@code WorkDayTest}가 시계를 고정해 경계값을 전부 시험한다.
 */
public final class WorkDay {

    /**
     * 지각 유예. 정책값이 아직 없어 0 — 06:00:01도 지각이다. 규정이 정해지면 여기 숫자만 바꾼다.
     * {@code WorkShift}가 정책 테이블로 가면(ADR-0005) 교대별 값으로 옮긴다.
     */
    public static final Duration LATE_GRACE = Duration.ZERO;

    /**
     * 출근 뒤 이 시간이 지나도록 퇴근이 없으면 그 기록은 "잊힌 출근"으로 본다. 퇴근 API는 이 기록을 닫지 않고
     * 거부한다({@code ATTENDANCE_CLOCK_IN_STALE}) — 어제 출근에 오늘 퇴근을 붙이면 30시간 근무 행이 생겨 집계가 오염된다.
     * 8시간 교대 + 잔업을 넉넉히 덮는 값. 정리는 관리자 수정 또는 미퇴근 배치(P3)의 일이다.
     */
    public static final Duration MAX_OPEN_SPAN = Duration.ofHours(16);

    private WorkDay() {
    }

    /** {@code at}이 속하는 근무일. 경계 시각 <b>이전</b>이면 전날이다(경계 시각 자체는 그날). */
    public static LocalDate of(WorkShift shift, LocalDateTime at) {
        return at.toLocalTime().isBefore(shift.getDayBoundary())
                ? at.toLocalDate().minusDays(1)
                : at.toLocalDate();
    }

    /** 근무일 {@code workDate}의 교대 시작 시각. 야간이면 그날 22:00이다(다음날이 아니다). */
    public static LocalDateTime shiftStart(WorkShift shift, LocalDate workDate) {
        return workDate.atTime(shift.getStartTime());
    }

    /**
     * 지각인가. 출근 시각이 속한 근무일의 교대 시작(+유예)보다 늦으면 지각.
     * 야간 00:30 출근은 근무일이 전날이므로 전날 22:00과 비교된다 — 시각만 비교하던 때는 이것이 정상 출근이었다.
     */
    public static boolean isLate(WorkShift shift, LocalDateTime clockIn) {
        LocalDateTime deadline = shiftStart(shift, of(shift, clockIn)).plus(LATE_GRACE);
        return clockIn.isAfter(deadline);
    }

    /** 출근 뒤 {@link #MAX_OPEN_SPAN}이 지났는가. */
    public static boolean isStale(LocalDateTime clockIn, LocalDateTime now) {
        return Duration.between(clockIn, now).compareTo(MAX_OPEN_SPAN) > 0;
    }
}
