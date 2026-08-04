package com.DOCKin.attendance.service;

import com.DOCKin.attendance.model.Attendance;
import com.DOCKin.attendance.repository.AttendanceRepository;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 미체크 인원을 결근({@code ABSENT}) 처리하는 자정 배치.
 *
 * <p><b>이 배치가 없으면 {@code AttendanceStatus.ABSENT}가 영원히 채워지지 않는다.</b>
 * 출근하지 않은 사람은 근태 행 자체가 만들어지지 않아 "기록이 없는 것"과 "결근한 것"이
 * 구분되지 않았다. 집계·급여 산정의 근거가 되려면 결근이 명시적인 행으로 남아야 한다.
 *
 * <p><b>{@code Clock}을 주입받는다.</b> {@code LocalDate.now()}를 직접 부르면 "어제"가 실행 시각에
 * 따라 달라져 테스트가 불가능하다. 근태는 날짜 경계가 곧 비즈니스 규칙이라 특히 중요하다.
 *
 * <h3>알려진 한계 — 근무일 판단</h3>
 * 이 프로젝트에는 <b>근무일 캘린더가 없다.</b> {@code WorkShift}는 교대 시간대만 정의하고
 * 공휴일·교대조별 휴무일 정보는 어디에도 없다. 그래서 지금은 <b>주말만 제외</b>한다.
 * 이대로면 공휴일에 전원이 결근 처리되므로, 운영에 쓰려면 근무일 캘린더가 선행되어야 한다.
 * ADR-0005가 지적한 "근무 정책 엔진 미충족"과 같은 뿌리의 문제다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceBatchService {

    private final AttendanceRepository attendanceRepository;
    private final MemberRepository memberRepository;
    private final Clock clock;

    @Value("${attendance.absent-batch.enabled:true}")
    private boolean batchEnabled;

    /**
     * 전날 미체크 인원을 결근 처리한다.
     *
     * <p>{@code @Transactional}을 이 메서드에 직접 붙였다. 내부에서 호출하는
     * {@link #markAbsentFor(LocalDate)}에만 붙이면 자기호출이라 프록시를 거치지 않아
     * 트랜잭션이 걸리지 않는다. 여기서 트랜잭션을 열면 내부 호출도 그 안에서 실행된다.
     */
    @Scheduled(cron = "${attendance.absent-batch.cron:0 10 0 * * *}")
    @Transactional
    public void markYesterdayAbsentees() {
        if (!batchEnabled) {
            log.info("[근태] 결근 배치가 비활성화되어 있어 건너뜁니다.");
            return;
        }
        markAbsentFor(LocalDate.now(clock).minusDays(1));
    }

    /**
     * 지정한 날짜에 근태 기록이 없는 인원을 결근 처리한다.
     *
     * <p>휴가로 이미 {@code VACATION}/{@code SICK} 기록이 생성된 사람은 조회 단계에서 걸러진다
     * — 승인된 휴가가 결근으로 뒤집히면 안 된다. 그래서 휴가 승인은 이 배치보다
     * <b>먼저 근태에 반영되어 있어야 하고</b>, 그 보장이 {@code AbsenceApprovedListener}가
     * 같은 트랜잭션에서 동기로 동작하는 이유다.
     *
     * @return 새로 생성한 결근 기록 수
     */
    @Transactional
    public int markAbsentFor(LocalDate workDate) {
        if (isNonWorkingDay(workDate)) {
            log.info("[근태] {}는 주말이라 결근 처리를 건너뜁니다.", workDate);
            return 0;
        }

        // 기록이 "있는" 사번만 사번 문자열로 투영해 가져온다. 엔티티를 전부 적재할 이유가 없다.
        Set<String> recorded = new HashSet<>(attendanceRepository.findUserIdsByWorkDate(workDate));

        List<Attendance> absentees = memberRepository.findAll().stream()
                .filter(member -> !recorded.contains(member.getUserId()))
                .map(member -> Attendance.ofAbsent(member, workDate))
                .toList();

        if (!absentees.isEmpty()) {
            attendanceRepository.saveAll(absentees);
        }

        log.info("[근태] 결근 처리 완료 - {} 대상 {}건 (기존 기록 {}건)",
                workDate, absentees.size(), recorded.size());
        return absentees.size();
    }

    /**
     * 주말 여부. <b>공휴일은 판단하지 못한다</b> — 근무일 캘린더가 없다.
     * 운영 투입 전에 반드시 보완해야 하는 지점이라 별도 메서드로 드러내 둔다.
     */
    private boolean isNonWorkingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
