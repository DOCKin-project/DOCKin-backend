package com.DOCKin.attendance.model;

import com.DOCKin.member.model.WorkShift;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 근무일 규칙(ADR-0010)의 경계값. Spring 없이 돈다.
 *
 * <p>표는 "야간조의 하루"를 따라간다: 7월 10일 21:30 조기 출근 → 22:00 시작 → 자정 → 06:00 종료 → 잔업 → 정오에 다음 근무일.
 * 주간·오후는 경계가 자정이라 벽시계 날짜와 같다는 것을 한 줄씩만 박아 둔다 — 그 두 교대는 이 변경으로 아무것도 안 바뀌어야 한다.
 */
class WorkDayTest {

    @ParameterizedTest(name = "NIGHT {0} → 근무일 {1}")
    @DisplayName("야간조 — 정오 이전은 전날 근무일, 정오부터 그날")
    @CsvSource({
            "2026-07-10T21:30, 2026-07-10", // 조기 출근
            "2026-07-10T22:00, 2026-07-10", // 정시
            "2026-07-10T23:59, 2026-07-10",
            "2026-07-11T00:00, 2026-07-10", // 자정 — 아직 같은 근무일
            "2026-07-11T00:30, 2026-07-10", // 지각 출근
            "2026-07-11T06:10, 2026-07-10", // 퇴근
            "2026-07-11T11:59, 2026-07-10", // 잔업 퇴근
            "2026-07-11T12:00, 2026-07-11", // 경계 시각 자체는 그날 — 다음 근무일
            "2026-07-11T21:50, 2026-07-11", // 다음 출근
    })
    void night_workDate(LocalDateTime at, LocalDate expected) {
        assertEquals(expected, WorkDay.of(WorkShift.NIGHT, at));
    }

    @ParameterizedTest(name = "{0} {1} → 근무일 {2}")
    @DisplayName("주간·오후 — 경계가 자정이라 근무일은 벽시계 날짜 그대로")
    @CsvSource({
            "MORNING,   2026-07-10T00:00, 2026-07-10",
            "MORNING,   2026-07-10T05:30, 2026-07-10",
            "MORNING,   2026-07-10T23:59, 2026-07-10",
            "AFTERNOON, 2026-07-10T00:00, 2026-07-10",
            "AFTERNOON, 2026-07-10T23:30, 2026-07-10", // 오후조 잔업 — 자정 전이면 그날
    })
    void dayShifts_workDateIsCalendarDate(WorkShift shift, LocalDateTime at, LocalDate expected) {
        assertEquals(expected, WorkDay.of(shift, at));
    }

    @Test
    @DisplayName("야간조 00:30 출근은 지각이다 — 근무일이 전날이라 전날 22:00과 비교된다")
    void night_afterMidnight_isLate() {
        assertTrue(WorkDay.isLate(WorkShift.NIGHT, LocalDateTime.of(2026, 7, 11, 0, 30)));
    }

    @Test
    @DisplayName("야간조 21:50 출근은 지각이 아니다")
    void night_beforeStart_isNotLate() {
        assertFalse(WorkDay.isLate(WorkShift.NIGHT, LocalDateTime.of(2026, 7, 10, 21, 50)));
    }

    @Test
    @DisplayName("정시는 지각이 아니고, 유예가 0이라 1초 뒤는 지각이다")
    void late_boundary_isExclusiveWithZeroGrace() {
        LocalDateTime start = LocalDateTime.of(2026, 7, 10, 6, 0);
        assertFalse(WorkDay.isLate(WorkShift.MORNING, start));
        assertTrue(WorkDay.isLate(WorkShift.MORNING, start.plusSeconds(1)));
    }

    @Test
    @DisplayName("야간조의 교대 시작은 근무일 당일 22:00이다 — 다음날이 아니다")
    void night_shiftStart_isOnWorkDate() {
        assertEquals(LocalDateTime.of(2026, 7, 10, 22, 0),
                WorkDay.shiftStart(WorkShift.NIGHT, LocalDate.of(2026, 7, 10)));
    }

    @Test
    @DisplayName("열린 기록은 출근 뒤 16시간까지 닫을 수 있고, 그 뒤는 잊힌 출근이다")
    void stale_boundary() {
        LocalDateTime in = LocalDateTime.of(2026, 7, 10, 22, 0);
        assertFalse(WorkDay.isStale(in, in.plusHours(16)));
        assertTrue(WorkDay.isStale(in, in.plusHours(16).plusSeconds(1)));
    }

    @Test
    @DisplayName("교대 종료 시각은 시작 + 8시간. 야간은 06:00이다(날짜 없음)")
    void endTime_isStartPlusEight() {
        assertEquals(java.time.LocalTime.of(14, 0), WorkShift.MORNING.getEndTime());
        assertEquals(java.time.LocalTime.of(22, 0), WorkShift.AFTERNOON.getEndTime());
        assertEquals(java.time.LocalTime.of(6, 0), WorkShift.NIGHT.getEndTime());
    }
}
