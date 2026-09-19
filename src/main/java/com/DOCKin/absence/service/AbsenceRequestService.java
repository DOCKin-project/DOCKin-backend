package com.DOCKin.absence.service;

import com.DOCKin.absence.dto.AbsenceRequestCreateRequestDto;
import com.DOCKin.absence.dto.AbsenceRequestResponseDto;
import com.DOCKin.absence.model.AbsenceRequest;
import com.DOCKin.absence.model.AbsenceStatus;
import com.DOCKin.absence.model.AbsenceType;
import com.DOCKin.absence.event.AbsenceApprovedEvent;
import com.DOCKin.absence.event.AbsenceCancelledEvent;
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

import java.time.Clock;
import java.time.LocalDate;
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
    private final Clock clock;

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
            // 취소 때 돌려줄 값. 재계산이 아니라 기록이어야 한다(캘린더가 그 사이 바뀔 수 있다).
            request.setDeductedDays(days);
        }

        request.setStatus(AbsenceStatus.APPROVED);
        request.setProcessedBy(admin);
        request.setProcessedAt(LocalDateTime.now(clock));
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
     * 신청자 취소. PENDING은 언제나, APPROVED는 <b>시작일 전까지</b>.
     *
     * <p>PENDING 취소는 기간 점유를 푸는 것뿐이다(겹침 검사가 PENDING도 본다). APPROVED 취소는 승인의 역이다 —
     * 깎은 연차를 돌려주고({@code deducted_days}, 승인 때 기록한 값) 승인이 만든 근태 행을 지운다
     * ({@link AbsenceCancelledEvent} → {@code AbsenceCancelledListener}, 승인과 같은 동기·같은 트랜잭션).
     * 이미 시작한 휴가는 근태가 사실이 됐으므로 409 — 그 뒤는 관리자 근태 수정의 영역(P3).
     *
     * <p>남의 신청은 존재 여부와 무관하게 403. 행은 남고 CANCELLED가 된다.
     */
    @Transactional
    public AbsenceRequestResponseDto cancelRequest(String userId, Integer requestId, String comment) {
        AbsenceRequest request = absenceRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ABSENCE_REQUEST_NOT_FOUND));
        if (!request.getMember().getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (request.getStatus() != AbsenceStatus.PENDING && request.getStatus() != AbsenceStatus.APPROVED) {
            throw new BusinessException(ErrorCode.ABSENCE_NOT_CANCELLABLE);
        }
        return cancel(request, request.getMember(), comment);
    }

    /**
     * 관리자 취소 = 승인 철회. APPROVED만(PENDING은 거절이 있다), 시작일 전까지. 부수효과는 신청자 취소와 같다.
     */
    @Transactional
    public AbsenceRequestResponseDto cancelAsAdmin(String adminUserId, Integer requestId, String comment) {
        Member admin = requireAdmin(adminUserId);
        AbsenceRequest request = absenceRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ABSENCE_REQUEST_NOT_FOUND));
        if (request.getStatus() != AbsenceStatus.APPROVED) {
            throw new BusinessException(ErrorCode.ABSENCE_NOT_CANCELLABLE);
        }
        return cancel(request, admin, comment);
    }

    /** 취소의 본문. PENDING이면 상태만, APPROVED면 환급 + 근태 되돌림. */
    private AbsenceRequestResponseDto cancel(AbsenceRequest request, Member actor, String comment) {
        boolean wasApproved = request.getStatus() == AbsenceStatus.APPROVED;
        if (wasApproved) {
            if (!request.getStartDate().isAfter(LocalDate.now(clock))) {
                throw new BusinessException(ErrorCode.ABSENCE_ALREADY_STARTED);
            }
            if (request.getType() == AbsenceType.VACATION) {
                // 차감과 같은 락. 관리자 승인과 본인 취소가 같은 사용자의 잔액을 동시에 만지면 lost update다.
                Member applicant = memberRepository.findByUserIdForUpdate(request.getMember().getUserId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
                applicant.refundLeaveDays(refundableDays(request));
            }
        }

        request.setStatus(AbsenceStatus.CANCELLED);
        request.setProcessedBy(actor);
        request.setProcessedAt(LocalDateTime.now(clock));
        request.setDecisionComment(comment);
        AbsenceRequestResponseDto response =
                AbsenceRequestResponseDto.fromEntity(absenceRequestRepository.save(request));

        if (wasApproved) {
            eventPublisher.publishEvent(new AbsenceCancelledEvent(
                    request.getMember().getUserId(), request.getType(),
                    request.getStartDate(), request.getEndDate()));
        }
        return response;
    }

    /** 승인 때 기록한 일수. V17 이전에 승인된 건은 기록이 없어 승인 때와 같은 규칙으로 다시 센다. */
    private int refundableDays(AbsenceRequest request) {
        if (request.getDeductedDays() != null) {
            return request.getDeductedDays();
        }
        return workCalendarService.workingDaysBetween(request.getStartDate(), request.getEndDate()).size();
    }

    @Transactional
    public AbsenceRequestResponseDto rejectRequest(String adminUserId, Integer requestId, String comment) {
        Member admin = requireAdmin(adminUserId);
        AbsenceRequest request = requirePendingRequest(requestId);

        request.setStatus(AbsenceStatus.REJECTED);
        request.setProcessedBy(admin);
        request.setProcessedAt(LocalDateTime.now(clock));
        request.setDecisionComment(comment);

        return AbsenceRequestResponseDto.fromEntity(absenceRequestRepository.save(request));
    }
}
