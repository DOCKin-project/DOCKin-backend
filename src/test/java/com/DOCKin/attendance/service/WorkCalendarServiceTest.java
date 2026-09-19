package com.DOCKin.attendance.service;

import com.DOCKin.attendance.model.DayType;
import com.DOCKin.attendance.model.WorkCalendar;
import com.DOCKin.attendance.repository.WorkCalendarRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkCalendarServiceTest {

    /** 2026-08-12 수요일 */
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 12);
    /** 2026-08-15 토요일 */
    private static final LocalDate SATURDAY = LocalDate.of(2026, 8, 15);

    @Mock
    private WorkCalendarRepository workCalendarRepository;
    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private WorkCalendarService workCalendarService;

    @Test
    @DisplayName("등록되지 않은 평일은 근무일이다")
    void 미등록_평일() {
        when(workCalendarRepository.findById(WEDNESDAY)).thenReturn(Optional.empty());

        // 캘린더를 비워둬도 기존 동작이 유지되어야 점진적으로 채워 넣을 수 있다.
        assertTrue(workCalendarService.isWorkingDay(WEDNESDAY));
    }

    @Test
    @DisplayName("등록되지 않은 주말은 휴무일이다")
    void 미등록_주말() {
        when(workCalendarRepository.findById(SATURDAY)).thenReturn(Optional.empty());

        assertFalse(workCalendarService.isWorkingDay(SATURDAY));
    }

    @Test
    @DisplayName("평일이어도 공휴일로 등록되어 있으면 휴무일이다")
    void 평일_공휴일() {
        when(workCalendarRepository.findById(WEDNESDAY))
                .thenReturn(Optional.of(entry(WEDNESDAY, DayType.HOLIDAY, "광복절 대체공휴일")));

        // 이 판단이 없으면 결근 배치가 공휴일에 전원을 결근 처리한다.
        assertFalse(workCalendarService.isWorkingDay(WEDNESDAY));
    }

    @Test
    @DisplayName("주말이어도 근무일로 등록되어 있으면 근무일이다")
    void 주말_특근() {
        when(workCalendarRepository.findById(SATURDAY))
                .thenReturn(Optional.of(entry(SATURDAY, DayType.WORKDAY, "특근")));

        // 캘린더는 양방향 예외를 표현해야 한다. 조선소는 토요일 특근이 있다.
        assertTrue(workCalendarService.isWorkingDay(SATURDAY));
    }

    @Test
    @DisplayName("회사 휴무일도 휴무로 취급한다")
    void 회사_휴무일() {
        when(workCalendarRepository.findById(WEDNESDAY))
                .thenReturn(Optional.of(entry(WEDNESDAY, DayType.COMPANY_HOLIDAY, "창립기념일")));

        assertFalse(workCalendarService.isWorkingDay(WEDNESDAY));
    }

    @Test
    @DisplayName("이미 등록된 날짜를 다시 등록하면 새로 만들지 않고 갱신한다")
    void 중복_등록은_갱신() {
        WorkCalendar existing = entry(WEDNESDAY, DayType.WORKDAY, "평일");
        when(memberRepository.findByUserId("admin1"))
                .thenReturn(Optional.of(Member.builder().userId("admin1").role(UserRole.ADMIN).build()));
        when(workCalendarRepository.findById(WEDNESDAY)).thenReturn(Optional.of(existing));

        workCalendarService.register("admin1", WEDNESDAY, DayType.HOLIDAY, "임시공휴일 지정");

        assertEquals(DayType.HOLIDAY, existing.getDayType());
        assertEquals("임시공휴일 지정", existing.getDescription());
        verify(workCalendarRepository, never()).save(any());
    }

    @Test
    @DisplayName("관리자가 아니면 캘린더를 등록할 수 없다")
    void 권한_없음() {
        when(memberRepository.findByUserId("user1"))
                .thenReturn(Optional.of(Member.builder().userId("user1").role(UserRole.USER).build()));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                workCalendarService.register("user1", WEDNESDAY, DayType.HOLIDAY, "임의 등록 시도"));

        assertEquals(ErrorCode.ACCESS_DENIED, ex.getErrorCode());
        verify(workCalendarRepository, never()).save(any());
    }

    @Test
    @DisplayName("일괄 등록은 저장된 엔티티를 요청 순서대로 돌려준다 - 있던 날은 갱신, 없던 날은 저장")
    void 일괄_등록_반환() {
        WorkCalendar existing = entry(WEDNESDAY, DayType.WORKDAY, "평일");
        WorkCalendar incoming = entry(SATURDAY, DayType.WORKDAY, "특근");
        when(memberRepository.findByUserId("admin1"))
                .thenReturn(Optional.of(Member.builder().userId("admin1").role(UserRole.ADMIN).build()));
        when(workCalendarRepository.findById(WEDNESDAY)).thenReturn(Optional.of(existing));
        when(workCalendarRepository.findById(SATURDAY)).thenReturn(Optional.empty());
        when(workCalendarRepository.save(incoming)).thenReturn(incoming);

        List<WorkCalendar> saved = workCalendarService.registerAll("admin1", List.of(
                entry(WEDNESDAY, DayType.HOLIDAY, "임시공휴일"), incoming));

        // 응답에 쓰려고 int 건수 대신 목록을 돌려준다. 있던 날은 새 객체가 아니라 그 엔티티다.
        assertEquals(List.of(existing, incoming), saved);
        assertEquals(DayType.HOLIDAY, existing.getDayType());
        verify(workCalendarRepository, never()).save(existing);
    }

    @Test
    @DisplayName("삭제하면 그 날은 기본 규칙으로 돌아간다 - 주말이면 휴무")
    void 삭제_후_기본_규칙() {
        WorkCalendar special = entry(SATURDAY, DayType.WORKDAY, "특근");
        when(memberRepository.findByUserId("admin1"))
                .thenReturn(Optional.of(Member.builder().userId("admin1").role(UserRole.ADMIN).build()));
        when(workCalendarRepository.findById(SATURDAY))
                .thenReturn(Optional.of(special))
                .thenReturn(Optional.empty());

        workCalendarService.delete("admin1", SATURDAY);

        verify(workCalendarRepository).delete(special);
        // 덮어쓰기로는 여기까지 못 돌아온다 — WEEKEND로 덮어쓴 날도 "등록된 날"이다.
        assertFalse(workCalendarService.isWorkingDay(SATURDAY));
    }

    @Test
    @DisplayName("등록되지 않은 날을 삭제하면 404 - 조용히 성공하면 오타 난 날짜를 못 알아챈다")
    void 미등록_삭제는_404() {
        when(memberRepository.findByUserId("admin1"))
                .thenReturn(Optional.of(Member.builder().userId("admin1").role(UserRole.ADMIN).build()));
        when(workCalendarRepository.findById(WEDNESDAY)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                workCalendarService.delete("admin1", WEDNESDAY));

        assertEquals(ErrorCode.WORK_CALENDAR_NOT_FOUND, ex.getErrorCode());
        verify(workCalendarRepository, never()).delete(any(WorkCalendar.class));
    }

    @Test
    @DisplayName("관리자가 아니면 삭제도 못 한다")
    void 삭제_권한_없음() {
        when(memberRepository.findByUserId("user1"))
                .thenReturn(Optional.of(Member.builder().userId("user1").role(UserRole.USER).build()));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                workCalendarService.delete("user1", WEDNESDAY));

        assertEquals(ErrorCode.ACCESS_DENIED, ex.getErrorCode());
        verify(workCalendarRepository, never()).delete(any(WorkCalendar.class));
    }

    @Test
    @DisplayName("기간의 근무일 - 등록된 날은 등록값, 나머지는 평일 규칙. 캘린더는 한 번만 읽는다 (#104)")
    void 기간_근무일() {
        // 2026-08-10 월 ~ 08-16 일. 수요일은 임시공휴일, 토요일은 특근으로 등록.
        LocalDate mon = LocalDate.of(2026, 8, 10);
        LocalDate sun = LocalDate.of(2026, 8, 16);
        when(workCalendarRepository.findByCalendarDateBetweenOrderByCalendarDate(mon, sun)).thenReturn(List.of(
                entry(WEDNESDAY, DayType.HOLIDAY, "임시공휴일"),
                entry(SATURDAY, DayType.WORKDAY, "특근")));

        List<LocalDate> days = workCalendarService.workingDaysBetween(mon, sun);

        // 월·화·목·금 + 토(특근) = 5. 수(공휴일)·일은 빠진다.
        assertEquals(List.of(mon, mon.plusDays(1), mon.plusDays(3), mon.plusDays(4), SATURDAY), days);
        verify(workCalendarRepository, never()).findById(any());
    }

    @Test
    @DisplayName("토~일만이면 근무일이 없다")
    void 주말만() {
        LocalDate sun = SATURDAY.plusDays(1);
        when(workCalendarRepository.findByCalendarDateBetweenOrderByCalendarDate(SATURDAY, sun)).thenReturn(List.of());

        assertTrue(workCalendarService.workingDaysBetween(SATURDAY, sun).isEmpty());
    }

    private WorkCalendar entry(LocalDate date, DayType type, String description) {
        return WorkCalendar.builder()
                .calendarDate(date)
                .dayType(type)
                .description(description)
                .build();
    }
}
