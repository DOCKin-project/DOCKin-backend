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
 * <h3>근무일 판단</h3>
 * {@link WorkCalendarService}에 위임한다. 캘린더에 등록된 날은 등록값을 따르고,
 * 없으면 기본 규칙(평일=근무, 주말=휴무)을 따른다.
 *
 * <p><b>남은 한계:</b> 캘린더는 전사 공통 휴무일만 다룬다. 조선소는 교대조마다 휴무 패턴이 달라
 * 실제로는 {@code (날짜, 교대조)} 단위로 근무일이 결정되지만, 그건 근무 정책 엔진의 영역이다
 * (ADR-0005 "근무 정책 엔진 미충족", 백로그 P3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceBatchService {

    private final AttendanceRepository attendanceRepository;
    private final MemberRepository memberRepository;
    private final WorkCalendarService workCalendarService;
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
        if (!workCalendarService.isWorkingDay(workDate)) {
            log.info("[근태] {}는 근무일이 아니라 결근 처리를 건너뜁니다.", workDate);
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
}
