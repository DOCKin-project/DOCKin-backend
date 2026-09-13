package com.DOCKin.attendance.service;

import com.DOCKin.absence.event.AbsenceApprovedEvent;
import com.DOCKin.absence.model.AbsenceType;
import com.DOCKin.attendance.model.Attendance;
import com.DOCKin.attendance.model.AttendanceStatus;
import com.DOCKin.attendance.repository.AttendanceRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbsenceApprovedListenerTest {

    private static final String USER_ID = "10001";

    @Mock
    private AttendanceRepository attendanceRepository;
    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private AbsenceApprovedListener listener;

    @Test
    @DisplayName("휴가가 승인되면 기간 전체에 VACATION 근태가 생성된다")
    void 휴가_승인_반영() {
        Member member = member();
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member));
        when(attendanceRepository.findByMemberAndWorkDateBetween(any(), any(), any()))
                .thenReturn(List.of());

        listener.onAbsenceApproved(new AbsenceApprovedEvent(
                USER_ID, AbsenceType.VACATION,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12)));

        List<Attendance> saved = captureSaved();
        assertEquals(3, saved.size(), "시작일과 종료일을 모두 포함해 3일이어야 한다");
        assertTrue(saved.stream().allMatch(a -> a.getStatus() == AttendanceStatus.VACATION));
        assertEquals(LocalDate.of(2026, 8, 10), saved.get(0).getWorkDate());
        assertEquals(LocalDate.of(2026, 8, 12), saved.get(2).getWorkDate());
    }

    @Test
    @DisplayName("휴가 근태에는 출근 시각이 없다")
    void 출근시각_없음() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(attendanceRepository.findByMemberAndWorkDateBetween(any(), any(), any()))
                .thenReturn(List.of());

        listener.onAbsenceApproved(new AbsenceApprovedEvent(
                USER_ID, AbsenceType.VACATION,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 10)));

        Attendance saved = captureSaved().get(0);
        // 더미 시각을 넣으면 "0시에 출근한 기록"이 되어 근무시간 집계를 오염시킨다.
        assertNull(saved.getClockInTime());
        assertNull(saved.getClockOutTime());
    }

    @Test
    @DisplayName("병가는 SICK으로 매핑된다")
    void 병가_매핑() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member()));
        when(attendanceRepository.findByMemberAndWorkDateBetween(any(), any(), any()))
                .thenReturn(List.of());

        listener.onAbsenceApproved(new AbsenceApprovedEvent(
                USER_ID, AbsenceType.SICK,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 10)));

        assertEquals(AttendanceStatus.SICK, captureSaved().get(0).getStatus());
    }

    @Test
    @DisplayName("이미 근태 기록이 있는 날은 덮어쓰지 않는다")
    void 기존_기록_보존() {
        Member member = member();
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member));
        // 8/11에 이미 출근 기록이 있다(소급 휴가 승인 상황).
        when(attendanceRepository.findByMemberAndWorkDateBetween(any(), any(), any()))
                .thenReturn(List.of(Attendance.builder()
                        .member(member)
                        .workDate(LocalDate.of(2026, 8, 11))
                        .status(AttendanceStatus.NORMAL)
                        .build()));

        listener.onAbsenceApproved(new AbsenceApprovedEvent(
                USER_ID, AbsenceType.VACATION,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12)));

        List<Attendance> saved = captureSaved();
        assertEquals(2, saved.size(), "이미 기록이 있는 하루는 제외되어야 한다");
        assertTrue(saved.stream().noneMatch(a -> a.getWorkDate().equals(LocalDate.of(2026, 8, 11))),
                "실제 출근 기록을 덮어쓰면 근무한 사실이 사라진다");
    }

    @Test
    @DisplayName("기간 전체에 이미 기록이 있으면 저장을 호출하지 않는다")
    void 생성할_것이_없으면_저장하지_않는다() {
        Member member = member();
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(member));
        when(attendanceRepository.findByMemberAndWorkDateBetween(any(), any(), any()))
                .thenReturn(List.of(Attendance.builder()
                        .member(member)
                        .workDate(LocalDate.of(2026, 8, 10))
                        .status(AttendanceStatus.NORMAL)
                        .build()));

        listener.onAbsenceApproved(new AbsenceApprovedEvent(
                USER_ID, AbsenceType.VACATION,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 10)));

        verify(attendanceRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("존재하지 않는 사용자의 승인 이벤트는 예외로 막는다")
    void 사용자_없음() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> listener.onAbsenceApproved(
                new AbsenceApprovedEvent(USER_ID, AbsenceType.VACATION,
                        LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 10))));
    }

    @SuppressWarnings("unchecked")
    private List<Attendance> captureSaved() {
        ArgumentCaptor<List<Attendance>> captor = ArgumentCaptor.forClass(List.class);
        verify(attendanceRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private Member member() {
        return Member.builder().userId(USER_ID).role(UserRole.USER).build();
    }
}
