package com.DOCKin.attendance.service;

import com.DOCKin.attendance.model.Attendance;
import com.DOCKin.attendance.model.AttendanceStatus;
import com.DOCKin.attendance.repository.AttendanceRepository;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceBatchServiceTest {

    /** 2026-08-12는 수요일. 평일 기준 테스트에 쓴다. */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 12);
    /** 2026-08-15는 토요일. */
    private static final LocalDate SATURDAY = LocalDate.of(2026, 8, 15);

    @Mock
    private AttendanceRepository attendanceRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private WorkCalendarService workCalendarService;

    @Test
    @DisplayName("근태 기록이 없는 인원만 결근 처리한다")
    void 미체크_인원만_결근() {
        AttendanceBatchService service = service(WEDNESDAY);
        when(workCalendarService.isWorkingDay(WEDNESDAY)).thenReturn(true);
        when(attendanceRepository.findUserIdsByWorkDate(WEDNESDAY))
                .thenReturn(List.of("10001"));   // 10001은 출근했다
        when(memberRepository.findAll())
                .thenReturn(List.of(member("10001"), member("10002"), member("10003")));

        int created = service.markAbsentFor(WEDNESDAY);

        assertEquals(2, created);
        List<Attendance> saved = captureSaved();
        assertTrue(saved.stream().allMatch(a -> a.getStatus() == AttendanceStatus.ABSENT));
        assertTrue(saved.stream().noneMatch(a -> a.getMember().getUserId().equals("10001")),
                "출근한 사람이 결근 처리되면 안 된다");
    }

    @Test
    @DisplayName("승인된 휴가로 기록이 있으면 결근으로 뒤집지 않는다")
    void 휴가자는_제외() {
        AttendanceBatchService service = service(WEDNESDAY);
        when(workCalendarService.isWorkingDay(WEDNESDAY)).thenReturn(true);
        // 휴가 승인 리스너가 이미 VACATION 기록을 만들어 둔 상태.
        when(attendanceRepository.findUserIdsByWorkDate(WEDNESDAY))
                .thenReturn(List.of("10002"));
        when(memberRepository.findAll())
                .thenReturn(List.of(member("10001"), member("10002")));

        service.markAbsentFor(WEDNESDAY);

        List<Attendance> saved = captureSaved();
        assertEquals(1, saved.size());
        assertEquals("10001", saved.get(0).getMember().getUserId(),
                "승인된 휴가가 결근으로 뒤집히면 안 된다");
    }

    @Test
    @DisplayName("결근 기록에는 출퇴근 시각이 없다")
    void 결근은_시각이_없다() {
        AttendanceBatchService service = service(WEDNESDAY);
        when(workCalendarService.isWorkingDay(WEDNESDAY)).thenReturn(true);
        when(attendanceRepository.findUserIdsByWorkDate(WEDNESDAY)).thenReturn(List.of());
        when(memberRepository.findAll()).thenReturn(List.of(member("10001")));

        service.markAbsentFor(WEDNESDAY);

        Attendance saved = captureSaved().get(0);
        assertNull(saved.getClockInTime());
        assertNull(saved.getClockOutTime());
    }

    @Test
    @DisplayName("근무일이 아니면 결근 처리하지 않는다 - 주말")
    void 주말_제외() {
        AttendanceBatchService service = service(SATURDAY);
        when(workCalendarService.isWorkingDay(SATURDAY)).thenReturn(false);

        int created = service.markAbsentFor(SATURDAY);

        assertEquals(0, created);
        // 근무일 판단이 먼저이므로 조회 자체가 일어나지 않아야 한다.
        verifyNoInteractions(attendanceRepository, memberRepository);
    }

    @Test
    @DisplayName("근무일이 아니면 결근 처리하지 않는다 - 평일 공휴일")
    void 공휴일_제외() {
        AttendanceBatchService service = service(WEDNESDAY);
        // 요일로는 평일이지만 캘린더가 공휴일로 등록해 둔 날.
        // 이 판단이 없으면 공휴일에 전원이 결근 처리된다.
        when(workCalendarService.isWorkingDay(WEDNESDAY)).thenReturn(false);

        assertEquals(0, service.markAbsentFor(WEDNESDAY));
        verifyNoInteractions(attendanceRepository, memberRepository);
    }

    @Test
    @DisplayName("전원이 출근했으면 저장을 호출하지 않는다")
    void 결근자가_없으면_저장하지_않는다() {
        AttendanceBatchService service = service(WEDNESDAY);
        when(workCalendarService.isWorkingDay(WEDNESDAY)).thenReturn(true);
        when(attendanceRepository.findUserIdsByWorkDate(WEDNESDAY))
                .thenReturn(List.of("10001", "10002"));
        when(memberRepository.findAll())
                .thenReturn(List.of(member("10001"), member("10002")));

        assertEquals(0, service.markAbsentFor(WEDNESDAY));
        verify(attendanceRepository, never()).saveAll(anyList());
    }

    /**
     * {@code Clock}을 고정해 생성한다.
     *
     * <p>고정 시각이 없으면 "어제"가 실행 시각에 따라 달라져 결과가 흔들린다.
     * 근태는 날짜 경계가 곧 비즈니스 규칙이라 특히 그렇다.
     */
    private AttendanceBatchService service(LocalDate today) {
        Clock fixed = Clock.fixed(
                today.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());
        return new AttendanceBatchService(attendanceRepository, memberRepository, workCalendarService, fixed);
    }

    @SuppressWarnings("unchecked")
    private List<Attendance> captureSaved() {
        ArgumentCaptor<List<Attendance>> captor = ArgumentCaptor.forClass(List.class);
        verify(attendanceRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private Member member(String userId) {
        return Member.builder().userId(userId).role(UserRole.USER).build();
    }
}
