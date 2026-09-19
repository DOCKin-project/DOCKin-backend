package com.DOCKin.attendance.service;

import com.DOCKin.absence.event.AbsenceCancelledEvent;
import com.DOCKin.attendance.model.AttendanceStatus;
import com.DOCKin.attendance.repository.AttendanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 승인 취소를 근태에서 되돌린다 — {@link AbsenceApprovedListener}의 역.
 *
 * <p>지우는 것은 <b>휴가가 만든 행만</b>이다: 상태가 VACATION/SICK이고 출근 시각이 없는 날. 취소는 시작일 전에만
 * 되므로 원래 그런 행뿐이어야 하지만, 조건을 좁혀 두면 "출근 찍힌 날을 휴가 취소가 지웠다"가 구조적으로 불가능하다.
 * 소급 승인이 건너뛴 날(이미 출근한 날, {@code AbsenceApprovedListener})은 애초에 휴가 행이 아니라 여기서도 그대로다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbsenceCancelledListener {

    private final AttendanceRepository attendanceRepository;

    @EventListener
    public void onAbsenceCancelled(AbsenceCancelledEvent event) {
        int deleted = attendanceRepository.deleteAbsenceRows(event.userId(), event.startDate(), event.endDate(),
                List.of(AttendanceStatus.VACATION, AttendanceStatus.SICK));
        log.info("[근태] 휴가 취소 반영 - userId={} {} {}~{} 근태 행 {}건 삭제",
                event.userId(), event.type(), event.startDate(), event.endDate(), deleted);
    }
}
