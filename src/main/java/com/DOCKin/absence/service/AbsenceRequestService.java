package com.DOCKin.absence.service;

import com.DOCKin.absence.dto.AbsenceRequestCreateRequestDto;
import com.DOCKin.absence.dto.AbsenceRequestResponseDto;
import com.DOCKin.absence.model.AbsenceRequest;
import com.DOCKin.absence.model.AbsenceStatus;
import com.DOCKin.absence.model.AbsenceType;
import com.DOCKin.absence.event.AbsenceApprovedEvent;
import com.DOCKin.absence.repository.AbsenceRequestRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.file.S3PresignedService;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

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

            // 잔여 연차는 읽고-검사하고-쓰는 순서라 락이 없으면 lost update가 난다.
            // 관리자 두 명이 같은 사용자의 신청 두 건을 동시에 승인하면 둘 다 검사를 통과하고
            // 둘 다 차감해 잔액이 음수가 될 수 있다. 유니크 제약으로는 막을 수 없는 종류의 문제다
            // (중복 행이 아니라 수치 갱신이므로). 상세는 findByUserIdForUpdate 주석 참고.
            Member applicant = memberRepository.findByUserIdForUpdate(request.getMember().getUserId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

            if (days > applicant.getRemainingLeaveDays()) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_LEAVE_DAYS);
            }
            applicant.useLeaveDays((int) days);
        }

        request.setStatus(AbsenceStatus.APPROVED);
        request.setProcessedBy(admin);
        request.setProcessedAt(LocalDateTime.now());
        request.setDecisionComment(comment);

        AbsenceRequestResponseDto response =
                AbsenceRequestResponseDto.fromEntity(absenceRequestRepository.save(request));

        // 승인만 하고 끝내면 그 기간은 근태에 아무 기록도 남지 않아 무단 결근 처리 대상이 된다.
        // 근태 모듈을 직접 호출하지 않고 이벤트로 알리되, 동기 리스너라 같은 트랜잭션에서 처리된다
        // (반영 실패 시 승인도 함께 롤백되어야 한다). 상세는 AbsenceApprovedEvent 참고.
        eventPublisher.publishEvent(new AbsenceApprovedEvent(
                request.getMember().getUserId(),
                request.getType(),
                request.getStartDate(),
                request.getEndDate()));

        return response;
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
