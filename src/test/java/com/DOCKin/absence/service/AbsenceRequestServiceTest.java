package com.DOCKin.absence.service;

import com.DOCKin.absence.dto.AbsenceRequestCreateRequestDto;
import com.DOCKin.absence.dto.AbsenceRequestResponseDto;
import com.DOCKin.absence.model.AbsenceRequest;
import com.DOCKin.absence.model.AbsenceStatus;
import com.DOCKin.absence.model.AbsenceType;
import com.DOCKin.absence.repository.AbsenceRequestRepository;
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
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbsenceRequestServiceTest {

    @Mock
    private AbsenceRequestRepository absenceRequestRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private S3PresignedService s3PresignedService;

    @InjectMocks
    private AbsenceRequestService absenceRequestService;

    private static final String ADMIN_ID = "admin1";
    private static final String USER_ID = "user1";

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
        AbsenceRequest request = pendingRequest(AbsenceType.VACATION,
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12), applicant); // 3일 신청
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(absenceRequestRepository.findById(1)).thenReturn(Optional.of(request));

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
        AbsenceRequest request = pendingRequest(AbsenceType.VACATION,
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12), applicant); // 3일
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(absenceRequestRepository.findById(1)).thenReturn(Optional.of(request));
        when(absenceRequestRepository.save(any(AbsenceRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        AbsenceRequestResponseDto response = absenceRequestService.approveRequest(ADMIN_ID, 1, "승인합니다");

        assertEquals(12, applicant.getRemainingLeaveDays());
        assertEquals("APPROVED", response.getStatus());
        assertEquals("승인합니다", response.getDecisionComment());
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
