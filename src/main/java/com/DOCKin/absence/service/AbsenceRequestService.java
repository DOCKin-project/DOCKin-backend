package com.DOCKin.absence.service;

import com.DOCKin.absence.dto.AbsenceRequestCreateRequestDto;
import com.DOCKin.absence.dto.AbsenceRequestResponseDto;
import com.DOCKin.absence.model.AbsenceRequest;
import com.DOCKin.absence.model.AbsenceStatus;
import com.DOCKin.absence.model.AbsenceType;
import com.DOCKin.absence.event.AbsenceApprovedEvent;
import com.DOCKin.absence.repository.AbsenceRequestRepository;
import com.DOCKin.attendance.service.WorkCalendarService;
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
import java.util.List;

// 휴가(연차/병가) 신청-승인 워크플로우. 승인 시점에만 잔여 연차를 검증/차감한다
// (신청 시점엔 날짜 유효성만 확인 - 여러 PENDING 요청이 잔액을 초과 예약하는 걸 승인 시점 검증으로 막는다).
//
// 일수는 달력 일수가 아니라 근무일 수다(#104, 2026-09-19). 월~일 7일을 내면 5일(공휴일이 끼면 그만큼 덜)을 깎는다.
// 근무일 판단은 WorkCalendarService 한 곳 — 결근 배치·하루 집계와 같은 기준이어야 "휴가인데 결근"이 안 난다.
// 이 전에 승인된 건은 소급 정정하지 않는다(사용자 결정).
//
// 기간 겹침은 세 겹으로 막는다 — 신청 시(PENDING·APPROVED와 겹치면 409), 승인 시(APPROVED와 재검사),
// DB(V11 EXCLUDE, APPROVED끼리). 겹치는 두 건이 둘 다 승인되면 근태 쪽은 기존 행을 건너뛰어 조용히 넘어가고
// 잔액만 두 번 깎였다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AbsenceRequestService {
    private final AbsenceRequestRepository absenceRequestRepository;
    private final MemberRepository memberRepository;
    private final S3PresignedService s3PresignedService;
    private final ApplicationEventPublisher eventPublisher;
    private final WorkCalendarService workCalendarService;

    /** 신청 시 겹침 상대. REJECTED는 자리를 차지하지 않는다. */
    private static final List<AbsenceStatus> OCCUPYING = List.of(AbsenceStatus.PENDING, AbsenceStatus.APPROVED);

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
        // 차감할 게 없는 연차는 실수다 — 토~일만 낸 신청. 신청 때 바로 알려야 고친다. 병가는 차감이 없어 안 본다.
        if (dto.getType() == AbsenceType.VACATION
                && workCalendarService.workingDaysBetween(dto.getStartDate(), dto.getEndDate()).isEmpty()) {
            throw new BusinessException(ErrorCode.ABSENCE_NO_WORKING_DAYS);
        }
        if (absenceRequestRepository.existsOverlapping(userId, dto.getStartDate(), dto.getEndDate(), OCCUPYING, 0)) {
            throw new BusinessException(ErrorCode.ABSENCE_PERIOD_OVERLAP);
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
        String applicantId = request.getMember().getUserId();

        // 신청 때 검사했어도 그 사이 다른 PENDING이 먼저 승인됐을 수 있다. APPROVED끼리만 본다 —
        // 겹치는 PENDING 둘 중 하나가 승인되면 나머지는 여기서 409로 걸린다.
        if (absenceRequestRepository.existsOverlapping(applicantId, request.getStartDate(), request.getEndDate(),
                List.of(AbsenceStatus.APPROVED), request.getRequestId())) {
            throw new BusinessException(ErrorCode.ABSENCE_PERIOD_OVERLAP);
        }

        if (request.getType() == AbsenceType.VACATION) {
            int days = workCalendarService.workingDaysBetween(request.getStartDate(), request.getEndDate()).size();

            // 잔여 연차는 읽고-검사하고-쓰는 순서라 락이 없으면 lost update가 난다.
            // 관리자 두 명이 같은 사용자의 신청 두 건을 동시에 승인하면 둘 다 검사를 통과하고
            // 둘 다 차감해 잔액이 음수가 될 수 있다. 유니크 제약으로는 막을 수 없는 종류의 문제다
            // (중복 행이 아니라 수치 갱신이므로). 상세는 findByUserIdForUpdate 주석 참고.
            Member applicant = memberRepository.findByUserIdForUpdate(applicantId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

            if (days > applicant.getRemainingLeaveDays()) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_LEAVE_DAYS);
            }
            applicant.useLeaveDays(days);
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

    /**
     * 신청자 취소 — PENDING만. 잘못 낸 신청이 관리자가 거절해 줄 때까지 기간을 점유하던 것(겹침 검사가 PENDING도 본다)을
     * 신청자가 스스로 거둘 수 있게 한다. 행은 남고 CANCELLED가 된다 — 이력은 지우지 않는다.
     *
     * <p>남의 신청은 존재 여부와 무관하게 403. APPROVED 취소(연차 환급·근태 행 삭제)는 다음 PR.
     */
    @Transactional
    public AbsenceRequestResponseDto cancelRequest(String userId, Integer requestId, String comment) {
        AbsenceRequest request = absenceRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ABSENCE_REQUEST_NOT_FOUND));
        if (!request.getMember().getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (request.getStatus() != AbsenceStatus.PENDING) {
            throw new BusinessException(ErrorCode.ABSENCE_NOT_CANCELLABLE);
        }

        request.setStatus(AbsenceStatus.CANCELLED);
        request.setProcessedBy(request.getMember());
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
