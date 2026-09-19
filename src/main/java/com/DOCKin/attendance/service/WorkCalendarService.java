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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /**
     * 기간(양끝 포함) 중 근무일만. {@link #isWorkingDay}와 같은 규칙인데 캘린더는 기간 한 번만 읽는다 —
     * 날짜마다 {@code findById}면 연차 기간만큼 쿼리가 는다. 연차 일수(#104)와 승인 근태 행이 이걸 쓴다.
     */
    public List<LocalDate> workingDaysBetween(LocalDate start, LocalDate endInclusive) {
        Map<LocalDate, WorkCalendar> registered = workCalendarRepository
                .findByCalendarDateBetweenOrderByCalendarDate(start, endInclusive).stream()
                .collect(Collectors.toMap(WorkCalendar::getCalendarDate, Function.identity()));
        return start.datesUntil(endInclusive.plusDays(1))
                .filter(date -> {
                    WorkCalendar entry = registered.get(date);
                    return entry != null ? entry.isWorkingDay() : !isWeekend(date);
                })
                .toList();
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

    /**
     * 연초 일괄 등록용. 공휴일 목록을 한 번에 넣는다.
     *
     * <p>순서대로 처리한다. 같은 날짜가 두 번 오면 첫 번째 {@code save}가 영속성 컨텍스트에 남아
     * 두 번째 {@code findById}가 그것을 찾으므로 뒤가 이긴다. 돌려주는 목록은 요청 순서·건수 그대로다 —
     * 중복 날짜는 같은 엔티티가 두 번 들어 있다.
     */
    @Transactional
    public List<WorkCalendar> registerAll(String adminUserId, List<WorkCalendar> entries) {
        requireAdmin(adminUserId);
        List<WorkCalendar> saved = entries.stream()
                .map(entry -> workCalendarRepository.findById(entry.getCalendarDate())
                        .map(existing -> {
                            existing.update(entry.getDayType(), entry.getDescription());
                            return existing;
                        })
                        .orElseGet(() -> workCalendarRepository.save(entry)))
                .toList();
        log.info("[근태] 근무일 캘린더 {}건 등록/갱신", saved.size());
        return saved;
    }

    /**
     * 캘린더에서 날짜를 지운다. 그 날은 기본 규칙(평일=근무, 주말=휴무)으로 돌아간다.
     *
     * <p>덮어쓰기로는 되돌릴 수 없다 — {@code WEEKEND}나 {@code WORKDAY}로 덮어쓴 날도 여전히
     * "등록된 날"이라 기본 규칙이 바뀌어도(주 4일제 등) 따라가지 않는다. 잘못 등록한 날을
     * 되돌리는 길은 삭제뿐이다. 없는 날은 404 — 조용히 성공하면 오타 난 날짜를 못 알아챈다.
     */
    @Transactional
    public void delete(String adminUserId, LocalDate date) {
        requireAdmin(adminUserId);
        WorkCalendar entry = workCalendarRepository.findById(date)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_CALENDAR_NOT_FOUND));
        workCalendarRepository.delete(entry);
        log.info("[근태] 근무일 캘린더 {} 삭제", date);
    }

    private void requireAdmin(String userId) {
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (member.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }
}
