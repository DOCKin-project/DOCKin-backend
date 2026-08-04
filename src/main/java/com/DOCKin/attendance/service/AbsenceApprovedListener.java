package com.DOCKin.attendance.service;

import com.DOCKin.absence.event.AbsenceApprovedEvent;
import com.DOCKin.attendance.model.Attendance;
import com.DOCKin.attendance.model.AttendanceStatus;
import com.DOCKin.attendance.repository.AttendanceRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 휴가 승인을 근태에 반영한다.
 *
 * <p><b>이 리스너가 없으면 승인된 휴가일이 무단 결근으로 처리된다.</b>
 * {@code AttendanceStatus.VACATION}은 정의만 되어 있고 채우는 곳이 없었으며,
 * {@code absence} 패키지에는 {@code Attendance} 참조가 한 곳도 없었다.
 * 두 도메인이 각각 완성되어 있는데 사이가 비어 있던 지점이다.
 *
 * <p><b>동기 리스너({@code @EventListener})인 이유:</b> 승인과 같은 트랜잭션에서 실행되어야 한다.
 * 비동기나 {@code AFTER_COMMIT}으로 두면 근태 반영이 실패했을 때 승인만 남고,
 * 그 결과 <b>승인된 휴가인데 결근 처리</b>되는 상태가 된다. 결합도만 낮추고 원자성은 유지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbsenceApprovedListener {

    private final AttendanceRepository attendanceRepository;
    private final MemberRepository memberRepository;

    @EventListener
    public void onAbsenceApproved(AbsenceApprovedEvent event) {
        Member member = memberRepository.findByUserId(event.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        AttendanceStatus status = toAttendanceStatus(event.type());

        // 기간 전체의 기존 기록을 한 번에 조회한다. 날짜별로 조회하면 기간만큼 쿼리가 늘어난다.
        Set<LocalDate> alreadyRecorded = attendanceRepository
                .findByMemberAndWorkDateBetween(member, event.startDate(), event.endDate())
                .stream()
                .map(Attendance::getWorkDate)
                .collect(Collectors.toSet());

        // 이미 기록이 있는 날은 건드리지 않는다.
        // 소급 승인(이미 출근한 날에 대한 휴가 승인)에서 실제 출퇴근 기록을 덮어쓰면
        // 근무한 사실이 사라진다. 덮어쓰기는 관리자의 명시적 수정으로 처리할 문제다.
        List<Attendance> toCreate = event.startDate()
                .datesUntil(event.endDate().plusDays(1))
                .filter(date -> !alreadyRecorded.contains(date))
                .map(date -> Attendance.ofApprovedAbsence(member, date, status))
                .toList();

        if (!toCreate.isEmpty()) {
            attendanceRepository.saveAll(toCreate);
        }

        log.info("[근태] 휴가 승인 반영 - userId={} {} {}~{} 생성 {}건, 기존 기록으로 건너뜀 {}건",
                event.userId(), status, event.startDate(), event.endDate(),
                toCreate.size(), alreadyRecorded.size());
    }

    /**
     * 휴가 종류를 근태 상태로 매핑한다.
     *
     * <p>{@code AbsenceType}과 {@code AttendanceStatus}는 이름이 같은 값을 갖지만 별개의 enum이다.
     * 근태에는 NORMAL/LATE/ABSENT 등 휴가와 무관한 값이 있어 하나로 합칠 수 없다.
     */
    private AttendanceStatus toAttendanceStatus(com.DOCKin.absence.model.AbsenceType type) {
        return switch (type) {
            case VACATION -> AttendanceStatus.VACATION;
            case SICK -> AttendanceStatus.SICK;
        };
    }
}
