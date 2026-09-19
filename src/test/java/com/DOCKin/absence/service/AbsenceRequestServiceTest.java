package com.DOCKin.absence.service;

import com.DOCKin.absence.dto.AbsenceRequestCreateRequestDto;
import com.DOCKin.absence.dto.AbsenceRequestResponseDto;
import com.DOCKin.absence.model.AbsenceRequest;
import com.DOCKin.absence.model.AbsenceStatus;
import com.DOCKin.absence.model.AbsenceType;
import com.DOCKin.absence.repository.AbsenceRequestRepository;
import com.DOCKin.attendance.service.WorkCalendarService;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.file.S3PresignedService;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbsenceRequestServiceTest {

    @Mock
    private AbsenceRequestRepository absenceRequestRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private S3PresignedService s3PresignedService;
    /**
     * 승인 시 {@code AbsenceApprovedEvent}를 발행해 근태에 반영한다.
     * 발행 자체는 여기서 검증하지 않고 {@code AbsenceApprovedListenerTest}가 수신 측을 검증한다.
     */
    @Mock
    private ApplicationEventPublisher eventPublisher;
    /** 일수는 근무일 수다(#104). 여기서는 목 — 규칙 자체는 {@code WorkCalendarServiceTest}가 본다. */
    @Mock
    private WorkCalendarService workCalendarService;

    @InjectMocks
    private AbsenceRequestService absenceRequestService;

    private static final String ADMIN_ID = "admin1";
    private static final String USER_ID = "user1";

    /** 2026-07-13 월 ~ 07-15 수. 근무일 3. */
    private static final LocalDate MON = LocalDate.of(2026, 7, 13);
    private static final LocalDate WED = LocalDate.of(2026, 7, 15);
    /** 2026-07-18 토 ~ 07-19 일. 근무일 0. */
    private static final LocalDate SAT = LocalDate.of(2026, 7, 18);
    private static final LocalDate SUN = LocalDate.of(2026, 7, 19);

    private void workingDays(LocalDate start, LocalDate end, LocalDate... days) {
        when(workCalendarService.workingDaysBetween(start, end)).thenReturn(List.of(days));
    }

    private Member admin() {
        return Member.builder().userId(ADMIN_ID).role(UserRole.ADMIN).build();
    }

    private Member applicant(int remainingLeaveDays) {
        return Member.builder().userId(USER_ID).role(UserRole.USER).remainingLeaveDays(remainingLeaveDays).build();
    }

    private AbsenceRequest pendingRequest(AbsenceType type, LocalDate start, LocalDate end, Member member) {
        return AbsenceRequest.builder()
                .requestId(1)
                .member(member)
                .type(type)
                .startDate(start)
                .endDate(end)
                .reason("사유")
                .status(AbsenceStatus.PENDING)
                .build();
    }

    @Test
    @DisplayName("종료일이 시작일보다 빠르면 INVALID_DATE_RANGE 예외가 발생한다")
    void createRequest_invalidDateRange_throwsException() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(applicant(15)));
        AbsenceRequestCreateRequestDto dto = AbsenceRequestCreateRequestDto.builder()
                .type(AbsenceType.VACATION)
                .startDate(LocalDate.of(2026, 7, 10))
                .endDate(LocalDate.of(2026, 7, 5))
                .reason("사유").build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> absenceRequestService.createRequest(USER_ID, dto, null));

        assertEquals(ErrorCode.INVALID_DATE_RANGE, ex.getErrorCode());
        verify(absenceRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("증빙서류를 첨부하면 S3에 업로드되고 문서 URL이 저장된다")
    void createRequest_withDocument_uploadsFile() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(applicant(15)));
        MockMultipartFile file = new MockMultipartFile("document", "proof.pdf", "application/pdf", "dummy".getBytes());
        when(s3PresignedService.uploadImage(file)).thenReturn("https://s3/proof-uuid.pdf");
        when(absenceRequestRepository.save(any(AbsenceRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        AbsenceRequestCreateRequestDto dto = AbsenceRequestCreateRequestDto.builder()
                .type(AbsenceType.SICK)
                .startDate(LocalDate.of(2026, 7, 10))
                .endDate(LocalDate.of(2026, 7, 10))
                .reason("병원 진료").build();

        AbsenceRequestResponseDto response = absenceRequestService.createRequest(USER_ID, dto, file);

        assertEquals("https://s3/proof-uuid.pdf", response.getDocumentUrl());
        verify(s3PresignedService, times(1)).uploadImage(file);
    }

    @Test
    @DisplayName("문서 없이 신청하면 업로드를 호출하지 않고 documentUrl은 null이다")
    void createRequest_withoutDocument_skipsUpload() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(applicant(15)));
        when(absenceRequestRepository.save(any(AbsenceRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        workingDays(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 11), LocalDate.of(2026, 7, 10));
        AbsenceRequestCreateRequestDto dto = AbsenceRequestCreateRequestDto.builder()
                .type(AbsenceType.VACATION)
                .startDate(LocalDate.of(2026, 7, 10))
                .endDate(LocalDate.of(2026, 7, 11))
                .reason("개인 사정").build();

        AbsenceRequestResponseDto response = absenceRequestService.createRequest(USER_ID, dto, null);

        assertNull(response.getDocumentUrl());
        verify(s3PresignedService, never()).uploadImage(any());
    }

    @Test
    @DisplayName("관리자가 아닌 사용자가 승인하면 ABSENCE_REQUEST_AUTHOR 예외가 발생한다")
    void approveRequest_notAdmin_throwsException() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(applicant(15)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> absenceRequestService.approveRequest(USER_ID, 1, "ok"));

        assertEquals(ErrorCode.ABSENCE_REQUEST_AUTHOR, ex.getErrorCode());
        verify(absenceRequestRepository, never()).findById(any());
    }

    @Test
    @DisplayName("존재하지 않는 요청을 승인하려 하면 ABSENCE_REQUEST_NOT_FOUND 예외가 발생한다")
    void approveRequest_notFound_throwsException() {
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(absenceRequestRepository.findById(1)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> absenceRequestService.approveRequest(ADMIN_ID, 1, "ok"));

        assertEquals(ErrorCode.ABSENCE_REQUEST_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("이미 처리된 요청을 다시 승인하려 하면 ABSENCE_REQUEST_ALREADY_PROCESSED 예외가 발생한다")
    void approveRequest_alreadyProcessed_throwsException() {
        Member applicant = applicant(15);
        AbsenceRequest approved = pendingRequest(AbsenceType.VACATION,
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 11), applicant);
        approved.setStatus(AbsenceStatus.APPROVED);
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(absenceRequestRepository.findById(1)).thenReturn(Optional.of(approved));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> absenceRequestService.approveRequest(ADMIN_ID, 1, "ok"));

        assertEquals(ErrorCode.ABSENCE_REQUEST_ALREADY_PROCESSED, ex.getErrorCode());
    }

    @Test
    @DisplayName("연차 잔액이 부족하면 승인이 거부되고 잔액이 차감되지 않는다")
    void approveRequest_insufficientLeaveDays_throwsExceptionAndDoesNotDeduct() {
        Member applicant = applicant(1); // 잔여 1일
        AbsenceRequest request = pendingRequest(AbsenceType.VACATION, MON, WED, applicant); // 근무일 3일 신청
        workingDays(MON, WED, MON, MON.plusDays(1), WED);
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(absenceRequestRepository.findById(1)).thenReturn(Optional.of(request));
        // 잔액 갱신은 lost update를 막기 위해 비관적 락으로 다시 조회한 인스턴스에 대해 수행한다.
        when(memberRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(applicant));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> absenceRequestService.approveRequest(ADMIN_ID, 1, "ok"));

        assertEquals(ErrorCode.INSUFFICIENT_LEAVE_DAYS, ex.getErrorCode());
        assertEquals(1, applicant.getRemainingLeaveDays());
        verify(absenceRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("연차 잔액이 충분하면 승인되고 잔여 연차가 차감된다")
    void approveRequest_sufficientLeaveDays_deductsBalance() {
        Member applicant = applicant(15);
        AbsenceRequest request = pendingRequest(AbsenceType.VACATION, MON, WED, applicant); // 근무일 3일
        workingDays(MON, WED, MON, MON.plusDays(1), WED);
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(memberRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(applicant));
        when(absenceRequestRepository.findById(1)).thenReturn(Optional.of(request));
        when(absenceRequestRepository.save(any(AbsenceRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        AbsenceRequestResponseDto response = absenceRequestService.approveRequest(ADMIN_ID, 1, "승인합니다");

        assertEquals(12, applicant.getRemainingLeaveDays());
        assertEquals("APPROVED", response.getStatus());
        assertEquals("승인합니다", response.getDecisionComment());
    }

    @Test
    @DisplayName("월~일 7일을 내면 근무일 5일만 깎인다 - 달력 일수가 아니다 (#104)")
    void approveRequest_deductsWorkingDaysOnly() {
        Member applicant = applicant(15);
        AbsenceRequest request = pendingRequest(AbsenceType.VACATION, MON, SUN, applicant);
        workingDays(MON, SUN, MON, MON.plusDays(1), WED, WED.plusDays(1), WED.plusDays(2));
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(memberRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(applicant));
        when(absenceRequestRepository.findById(1)).thenReturn(Optional.of(request));
        when(absenceRequestRepository.save(any(AbsenceRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        absenceRequestService.approveRequest(ADMIN_ID, 1, "ok");

        // 전엔 DAYS.between+1 = 7이었다.
        assertEquals(10, applicant.getRemainingLeaveDays());
    }

    @Test
    @DisplayName("토~일만 낸 연차는 신청 시점에 400 - 차감할 게 없는 연차는 실수다")
    void createRequest_vacationWithoutWorkingDays_rejected() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(applicant(15)));
        workingDays(SAT, SUN);
        AbsenceRequestCreateRequestDto dto = AbsenceRequestCreateRequestDto.builder()
                .type(AbsenceType.VACATION).startDate(SAT).endDate(SUN).reason("주말").build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> absenceRequestService.createRequest(USER_ID, dto, null));

        assertEquals(ErrorCode.ABSENCE_NO_WORKING_DAYS, ex.getErrorCode());
        verify(absenceRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("토~일 병가는 된다 - 차감이 없으니 근무일 검사도 없다")
    void createRequest_sickOnWeekend_allowed() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(applicant(15)));
        when(absenceRequestRepository.save(any(AbsenceRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        AbsenceRequestCreateRequestDto dto = AbsenceRequestCreateRequestDto.builder()
                .type(AbsenceType.SICK).startDate(SAT).endDate(SUN).reason("입원").build();

        assertEquals("PENDING", absenceRequestService.createRequest(USER_ID, dto, null).getStatus());
        verify(workCalendarService, never()).workingDaysBetween(any(), any());
    }

    @Test
    @DisplayName("신청 기간이 내 PENDING·APPROVED와 겹치면 409 (#104)")
    void createRequest_overlapping_rejected() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(applicant(15)));
        workingDays(MON, WED, MON);
        when(absenceRequestRepository.existsOverlapping(eq(USER_ID), eq(MON), eq(WED),
                eq(List.of(AbsenceStatus.PENDING, AbsenceStatus.APPROVED)), eq(0))).thenReturn(true);
        AbsenceRequestCreateRequestDto dto = AbsenceRequestCreateRequestDto.builder()
                .type(AbsenceType.VACATION).startDate(MON).endDate(WED).reason("중복").build();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> absenceRequestService.createRequest(USER_ID, dto, null));

        assertEquals(ErrorCode.ABSENCE_PERIOD_OVERLAP, ex.getErrorCode());
        verify(absenceRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("승인 시 이미 승인된 건과 겹치면 409이고 잔액은 그대로 - 겹치는 PENDING 둘 중 하나만 승인된다")
    void approveRequest_overlappingApproved_rejected() {
        Member applicant = applicant(15);
        AbsenceRequest request = pendingRequest(AbsenceType.VACATION, MON, WED, applicant);
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(absenceRequestRepository.findById(1)).thenReturn(Optional.of(request));
        when(absenceRequestRepository.existsOverlapping(eq(USER_ID), eq(MON), eq(WED),
                eq(List.of(AbsenceStatus.APPROVED)), eq(1))).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> absenceRequestService.approveRequest(ADMIN_ID, 1, "ok"));

        assertEquals(ErrorCode.ABSENCE_PERIOD_OVERLAP, ex.getErrorCode());
        assertEquals(15, applicant.getRemainingLeaveDays());
        verify(memberRepository, never()).findByUserIdForUpdate(any());
        verify(absenceRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("병가는 잔여 연차 검증/차감 없이 승인된다")
    void approveRequest_sickType_doesNotAffectLeaveBalance() {
        Member applicant = applicant(0); // 잔여 0일이어도
        AbsenceRequest request = pendingRequest(AbsenceType.SICK,
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12), applicant);
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(absenceRequestRepository.findById(1)).thenReturn(Optional.of(request));
        when(absenceRequestRepository.save(any(AbsenceRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        AbsenceRequestResponseDto response = absenceRequestService.approveRequest(ADMIN_ID, 1, null);

        assertEquals(0, applicant.getRemainingLeaveDays());
        assertEquals("APPROVED", response.getStatus());
    }

    @Test
    @DisplayName("거절 처리 시 잔여 연차는 변동되지 않는다")
    void rejectRequest_doesNotAffectLeaveBalance() {
        Member applicant = applicant(15);
        AbsenceRequest request = pendingRequest(AbsenceType.VACATION,
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12), applicant);
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(absenceRequestRepository.findById(1)).thenReturn(Optional.of(request));
        when(absenceRequestRepository.save(any(AbsenceRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        AbsenceRequestResponseDto response = absenceRequestService.rejectRequest(ADMIN_ID, 1, "사유 불충분");

        assertEquals(15, applicant.getRemainingLeaveDays());
        assertEquals("REJECTED", response.getStatus());
        assertEquals("사유 불충분", response.getDecisionComment());
    }
}
