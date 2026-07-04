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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

// 휴가(연차/병가) 신청-승인 워크플로우. 승인 시점에만 잔여 연차를 검증/차감한다
// (신청 시점엔 날짜 유효성만 확인 - 여러 PENDING 요청이 잔액을 초과 예약하는 걸 승인 시점 검증으로 막는다).
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AbsenceRequestService {
    private final AbsenceRequestRepository absenceRequestRepository;
    private final MemberRepository memberRepository;
    private final S3PresignedService s3PresignedService;

    private Member requireAdmin(String userId) {
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (member.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.ABSENCE_REQUEST_AUTHOR);
        }
        return member;
    }

    private AbsenceRequest requirePendingRequest(Integer requestId) {
        AbsenceRequest request = absenceRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ABSENCE_REQUEST_NOT_FOUND));
        if (request.getStatus() != AbsenceStatus.PENDING) {
            throw new BusinessException(ErrorCode.ABSENCE_REQUEST_ALREADY_PROCESSED);
        }
        return request;
    }

    @Transactional
    public AbsenceRequestResponseDto createRequest(String userId, AbsenceRequestCreateRequestDto dto,
                                                    MultipartFile document) {
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (dto.getEndDate().isBefore(dto.getStartDate())) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        }

        String documentUrl = document != null && !document.isEmpty()
                ? s3PresignedService.uploadImage(document)
                : null;

        AbsenceRequest request = AbsenceRequest.builder()
                .member(member)
                .type(dto.getType())
                .startDate(dto.getStartDate())
                .endDate(dto.getEndDate())
                .reason(dto.getReason())
                .documentUrl(documentUrl)
                .status(AbsenceStatus.PENDING)
                .build();

        return AbsenceRequestResponseDto.fromEntity(absenceRequestRepository.save(request));
    }

    public Page<AbsenceRequestResponseDto> getMyRequests(String userId, Pageable pageable) {
        return absenceRequestRepository.findByMember_UserIdOrderByRequestedAtDesc(userId, pageable)
                .map(AbsenceRequestResponseDto::fromEntity);
    }

    public Page<AbsenceRequestResponseDto> getRequestsForAdmin(String adminUserId, AbsenceStatus status,
                                                                Pageable pageable) {
        requireAdmin(adminUserId);
        if (status != null) {
            return absenceRequestRepository.findByStatusOrderByRequestedAtAsc(status, pageable)
                    .map(AbsenceRequestResponseDto::fromEntity);
        }
        return absenceRequestRepository.findAll(pageable).map(AbsenceRequestResponseDto::fromEntity);
    }

    @Transactional
    public AbsenceRequestResponseDto approveRequest(String adminUserId, Integer requestId, String comment) {
        Member admin = requireAdmin(adminUserId);
        AbsenceRequest request = requirePendingRequest(requestId);

        if (request.getType() == AbsenceType.VACATION) {
            long days = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
            Member applicant = request.getMember();
            if (days > applicant.getRemainingLeaveDays()) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_LEAVE_DAYS);
            }
            applicant.useLeaveDays((int) days);
        }

        request.setStatus(AbsenceStatus.APPROVED);
        request.setProcessedBy(admin);
        request.setProcessedAt(LocalDateTime.now());
        request.setDecisionComment(comment);

        return AbsenceRequestResponseDto.fromEntity(absenceRequestRepository.save(request));
    }

    @Transactional
    public AbsenceRequestResponseDto rejectRequest(String adminUserId, Integer requestId, String comment) {
        Member admin = requireAdmin(adminUserId);
        AbsenceRequest request = requirePendingRequest(requestId);

        request.setStatus(AbsenceStatus.REJECTED);
        request.setProcessedBy(admin);
        request.setProcessedAt(LocalDateTime.now());
        request.setDecisionComment(comment);

        return AbsenceRequestResponseDto.fromEntity(absenceRequestRepository.save(request));
    }
}
