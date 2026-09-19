package com.DOCKin.attendance.service;

import com.DOCKin.attendance.dto.AttendanceDto;
import com.DOCKin.attendance.dto.ClockInRequestDto;
import com.DOCKin.attendance.dto.ClockOutRequestDto;
import com.DOCKin.attendance.model.Attendance;
import com.DOCKin.attendance.model.AttendanceStatus;
import com.DOCKin.attendance.repository.AttendanceRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.WorkShift;
import com.DOCKin.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {

    @Mock
    private AttendanceRepository attendanceRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private static final String USER_ID = "worker01";
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private Clock fixedClockAt(int hour, int minute) {
        LocalDateTime dateTime = LocalDateTime.of(2026, 7, 10, hour, minute);
        return Clock.fixed(dateTime.atZone(ZONE).toInstant(), ZONE);
    }

    private AttendanceService serviceWithClock(Clock clock) {
        return new AttendanceService(attendanceRepository, memberRepository, redissonClient, clock);
    }

    private Member member() {
        return Member.builder().userId(USER_ID).workShift(WorkShift.MORNING).build(); // MORNING 시작 06:00
    }

    private void stubLockAcquired() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(3L, 3L, TimeUnit.SECONDS)).thenReturn(true);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    @DisplayName("교대 시작 시각 이전에 출근하면 NORMAL로 기록된다")
    void clockin_beforeShiftStart_recordsNormal() {
        AttendanceService service = serviceWithClock(fixedClockAt(5, 0)); // MORNING(06:00) 이전
        stubLockAcquired();
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(attendanceRepository.findByMemberAndWorkDate(any(), any())).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        AttendanceDto response = service.clockin(USER_ID, ClockInRequestDto.builder().inLocation("1도크").build());

        assertEquals(AttendanceStatus.NORMAL.name(), response.getStatus());
    }

    @Test
    @DisplayName("교대 시작 시각 이후에 출근하면 LATE로 기록된다")
    void clockin_afterShiftStart_recordsLate() {
        AttendanceService service = serviceWithClock(fixedClockAt(7, 0)); // MORNING(06:00) 이후
        stubLockAcquired();
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(attendanceRepository.findByMemberAndWorkDate(any(), any())).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        AttendanceDto response = service.clockin(USER_ID, ClockInRequestDto.builder().inLocation("1도크").build());

        assertEquals(AttendanceStatus.LATE.name(), response.getStatus());
    }

    @Test
    @DisplayName("이미 오늘 출근 기록이 있으면 ATTENDANCE_ALREADY_CHECKED 예외가 발생한다")
    void clockin_alreadyCheckedToday_throwsException() {
        AttendanceService service = serviceWithClock(fixedClockAt(7, 0));
        stubLockAcquired();
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(attendanceRepository.findByMemberAndWorkDate(any(), any()))
                .thenReturn(Optional.of(Attendance.builder().build()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.clockin(USER_ID, ClockInRequestDto.builder().build()));

        assertEquals(ErrorCode.ATTENDANCE_ALREADY_CHECKED, ex.getErrorCode());
    }

    @Test
    @DisplayName("분산락 획득에 실패하면(동시 요청 처리 중) ATTENDANCE_ALREADY_CHECKED 예외가 발생하고 DB에 접근하지 않는다")
    void clockin_lockNotAcquired_throwsExceptionWithoutDbAccess() throws InterruptedException {
        AttendanceService service = serviceWithClock(fixedClockAt(7, 0));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(3L, 3L, TimeUnit.SECONDS)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.clockin(USER_ID, ClockInRequestDto.builder().build()));

        assertEquals(ErrorCode.ATTENDANCE_ALREADY_CHECKED, ex.getErrorCode());
        verify(memberRepository, never()).findByUserId(any());
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("Redis 락 사용이 불가능하면(예외 발생) DB 제약으로 폴백해 정상 처리된다")
    void clockin_redisUnavailable_fallsBackToDbOnly() throws InterruptedException {
        AttendanceService service = serviceWithClock(fixedClockAt(5, 0));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(3L, 3L, TimeUnit.SECONDS)).thenThrow(new RuntimeException("Redis connection refused"));
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(attendanceRepository.findByMemberAndWorkDate(any(), any())).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        AttendanceDto response = service.clockin(USER_ID, ClockInRequestDto.builder().build());

        assertEquals(AttendanceStatus.NORMAL.name(), response.getStatus());
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("DB 유니크 제약 위반 시(2차 방어선) ATTENDANCE_ALREADY_CHECKED로 변환된다")
    void clockin_uniqueConstraintViolation_convertsToBusinessException() {
        AttendanceService service = serviceWithClock(fixedClockAt(5, 0));
        stubLockAcquired();
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(attendanceRepository.findByMemberAndWorkDate(any(), any())).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.clockin(USER_ID, ClockInRequestDto.builder().build()));

        assertEquals(ErrorCode.ATTENDANCE_ALREADY_CHECKED, ex.getErrorCode());
    }

    @Test
    @DisplayName("정상 퇴근 시 근무 시간이 계산되어 저장된다")
    void clockout_success_calculatesTotalWorkTime() {
        AttendanceService service = serviceWithClock(fixedClockAt(15, 30));
        Member member = member();
        Attendance attendance = Attendance.builder()
                .member(member)
                .clockInTime(LocalDateTime.of(2026, 7, 10, 6, 0))
                .workDate(LocalDate.of(2026, 7, 10))
                .status(AttendanceStatus.NORMAL)
                .build();
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member));
        when(attendanceRepository.findByMemberAndWorkDate(eq(member), eq(LocalDate.of(2026, 7, 10))))
                .thenReturn(Optional.of(attendance));

        AttendanceDto response = service.clockout(USER_ID, ClockOutRequestDto.builder().outLocation("정문").build());

        assertEquals("09:30:00", response.getTotalWorkTime());
    }

    @Test
    @DisplayName("출근 기록이 없는 상태로 퇴근하려 하면 ATTENDANCE_NOT_CHECKED_IN 예외가 발생한다")
    void clockout_noClockInRecord_throwsException() {
        AttendanceService service = serviceWithClock(fixedClockAt(15, 30));
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(attendanceRepository.findByMemberAndWorkDate(any(), any())).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.clockout(USER_ID, ClockOutRequestDto.builder().build()));

        assertEquals(ErrorCode.ATTENDANCE_NOT_CHECKED_IN, ex.getErrorCode());
    }

    @Test
    @DisplayName("이미 퇴근 처리된 기록에 다시 퇴근하려 하면 ATTENDANCE_ALREADY_CHECKED_OUT 예외가 발생한다")
    void clockout_alreadyCheckedOut_throwsException() {
        AttendanceService service = serviceWithClock(fixedClockAt(18, 0));
        Attendance attendance = Attendance.builder()
                .clockInTime(LocalDateTime.of(2026, 7, 10, 6, 0))
                .clockOutTime(LocalDateTime.of(2026, 7, 10, 15, 0))
                .build();
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(attendanceRepository.findByMemberAndWorkDate(any(), any())).thenReturn(Optional.of(attendance));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.clockout(USER_ID, ClockOutRequestDto.builder().build()));

        assertEquals(ErrorCode.ATTENDANCE_ALREADY_CHECKED_OUT, ex.getErrorCode());
    }

    // ── 개인 근태 조회 — 기간 (P2-20-6) ─────────────────────────────────────────
    // 고정 시계는 2026-07-10. 상한 없이 전부 주던 것을 기간으로 바꿨으므로 "무엇을 기본으로 삼고 무엇을 거부하는가"를 박아 둔다.

    @Test
    @DisplayName("from·to 없음 - 오늘까지 최근 31일. 전부 주던 예전 동작이 아니다")
    void records_noRange_defaultsToLast31DaysEndingToday() {
        AttendanceService service = serviceWithClock(fixedClockAt(9, 0));
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(attendanceRepository.findByMemberAndWorkDateBetweenOrderByWorkDateDesc(any(), any(), any()))
                .thenReturn(java.util.List.of());

        service.getMyAttendanceRecords(USER_ID, null, null);

        verify(attendanceRepository).findByMemberAndWorkDateBetweenOrderByWorkDateDesc(
                any(), eq(LocalDate.of(2026, 6, 10)), eq(LocalDate.of(2026, 7, 10)));
        verify(attendanceRepository, never()).findByMemberAndWorkDateBetween(any(), any(), any());
    }

    @Test
    @DisplayName("to만 없음 - 오늘까지. from은 그대로")
    void records_toMissing_endsToday() {
        AttendanceService service = serviceWithClock(fixedClockAt(9, 0));
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(attendanceRepository.findByMemberAndWorkDateBetweenOrderByWorkDateDesc(any(), any(), any()))
                .thenReturn(java.util.List.of());

        service.getMyAttendanceRecords(USER_ID, LocalDate.of(2026, 7, 1), null);

        verify(attendanceRepository).findByMemberAndWorkDateBetweenOrderByWorkDateDesc(
                any(), eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 10)));
    }

    @Test
    @DisplayName("from > to - INVALID_DATE_RANGE. 회원 조회보다 먼저 거부한다")
    void records_fromAfterTo_throws() {
        AttendanceService service = serviceWithClock(fixedClockAt(9, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getMyAttendanceRecords(USER_ID, LocalDate.of(2026, 7, 11), LocalDate.of(2026, 7, 10)));

        assertEquals(ErrorCode.INVALID_DATE_RANGE, ex.getErrorCode());
        verify(memberRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("366일은 되고 367일은 ATTENDANCE_RANGE_TOO_LONG - 상한이 없으면 from=2000-01-01로 전부 다시 열린다")
    void records_rangeCap_is366DaysInclusive() {
        AttendanceService service = serviceWithClock(fixedClockAt(9, 0));
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(attendanceRepository.findByMemberAndWorkDateBetweenOrderByWorkDateDesc(any(), any(), any()))
                .thenReturn(java.util.List.of());
        LocalDate to = LocalDate.of(2026, 7, 10);

        service.getMyAttendanceRecords(USER_ID, to.minusDays(365), to); // 366일 포함 — 통과

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getMyAttendanceRecords(USER_ID, to.minusDays(366), to)); // 367일
        assertEquals(ErrorCode.ATTENDANCE_RANGE_TOO_LONG, ex.getErrorCode());
    }
}
