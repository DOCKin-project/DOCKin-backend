package com.DOCKin.attendance.service;

import com.DOCKin.attendance.model.DayType;
import com.DOCKin.attendance.model.WorkCalendar;
import com.DOCKin.attendance.repository.WorkCalendarRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * 근무일 판단과 캘린더 관리.
 *
 * <p>근무일 여부를 판단하는 지점을 여기 한 곳으로 모은다. 결근 배치 외에도
 * 초과근무 계산, 월말 집계 등이 같은 판단을 필요로 하게 되므로 각자 요일을 보게 두면 곧 어긋난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkCalendarService {

    private final WorkCalendarRepository workCalendarRepository;
    private final MemberRepository memberRepository;

    /**
     * 해당 날짜가 근무일인지 판단한다.
     *
     * <p>캘린더에 등록된 날은 등록값을 따르고, 없으면 기본 규칙(평일=근무, 주말=휴무)을 따른다.
     * 이 순서 덕분에 두 방향의 예외가 모두 표현된다 — 평일인데 쉬는 공휴일과
     * 주말인데 일하는 특근일이 둘 다 존재한다.
     *
     * <p>미등록을 근무일로 보는 것이 안전하다. 반대로 잡으면 캘린더를 채우기 전까지
     * 결근 배치가 아무도 처리하지 않고 <b>조용히 무력화</b>된다.
     */
    public boolean isWorkingDay(LocalDate date) {
        return workCalendarRepository.findById(date)
                .map(WorkCalendar::isWorkingDay)
                .orElseGet(() -> !isWeekend(date));
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    public List<WorkCalendar> findByYear(int year) {
        return workCalendarRepository.findByCalendarDateBetweenOrderByCalendarDate(
                LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    /**
     * 캘린더에 날짜를 등록하거나 갱신한다. 관리자만 가능하다.
     *
     * <p>날짜가 PK라 같은 날을 두 번 등록할 수 없다. 이미 있으면 갱신으로 처리해
     * 공휴일이 임시로 변경되는 경우(대체공휴일 지정 등)를 다룰 수 있게 한다.
     */
    @Transactional
    public WorkCalendar register(String adminUserId, LocalDate date, DayType dayType, String description) {
        requireAdmin(adminUserId);

        return workCalendarRepository.findById(date)
                .map(existing -> {
                    existing.update(dayType, description);
                    return existing;
                })
                .orElseGet(() -> workCalendarRepository.save(WorkCalendar.builder()
                        .calendarDate(date)
                        .dayType(dayType)
                        .description(description)
                        .build()));
    }

    /** 연초 일괄 등록용. 공휴일 목록을 한 번에 넣는다. */
    @Transactional
    public int registerAll(String adminUserId, List<WorkCalendar> entries) {
        requireAdmin(adminUserId);
        entries.forEach(entry -> workCalendarRepository.findById(entry.getCalendarDate())
                .ifPresentOrElse(
                        existing -> existing.update(entry.getDayType(), entry.getDescription()),
                        () -> workCalendarRepository.save(entry)));
        log.info("[근태] 근무일 캘린더 {}건 등록/갱신", entries.size());
        return entries.size();
    }

    private void requireAdmin(String userId) {
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (member.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }
}
