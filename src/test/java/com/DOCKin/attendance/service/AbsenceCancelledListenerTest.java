package com.DOCKin.attendance.service;

import com.DOCKin.absence.event.AbsenceCancelledEvent;
import com.DOCKin.absence.model.AbsenceType;
import com.DOCKin.attendance.model.AttendanceStatus;
import com.DOCKin.attendance.repository.AttendanceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 취소 리스너는 조건이 전부다 — "휴가 상태이고 출근 없는 행만". 삭제 쿼리의 실제 동작은
 * {@code AbsenceWorkingDaysAndOverlapTest}(컨테이너)가 승인→취소 왕복으로 본다.
 */
@ExtendWith(MockitoExtension.class)
class AbsenceCancelledListenerTest {

    @Mock
    private AttendanceRepository attendanceRepository;

    @InjectMocks
    private AbsenceCancelledListener listener;

    @Test
    @DisplayName("기간·휴가 상태(VACATION, SICK)·출근 없음 — 세 조건으로 지운다. 출근 찍힌 날은 이 경로로 지워질 수 없다")
    void deletesOnlyAbsenceRowsWithoutClockIn() {
        LocalDate start = LocalDate.of(2026, 8, 10);
        LocalDate end = LocalDate.of(2026, 8, 12);
        when(attendanceRepository.deleteAbsenceRows("10001", start, end,
                List.of(AttendanceStatus.VACATION, AttendanceStatus.SICK))).thenReturn(3);

        listener.onAbsenceCancelled(new AbsenceCancelledEvent("10001", AbsenceType.VACATION, start, end));

        verify(attendanceRepository).deleteAbsenceRows("10001", start, end,
                List.of(AttendanceStatus.VACATION, AttendanceStatus.SICK));
    }
}
