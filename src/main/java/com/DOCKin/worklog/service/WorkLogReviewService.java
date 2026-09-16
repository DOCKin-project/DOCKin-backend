package com.DOCKin.worklog.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.worklog.dto.WorkLogCursor;
import com.DOCKin.worklog.dto.WorkLogDto;
import com.DOCKin.worklog.event.WorkLogReviewed;
import com.DOCKin.worklog.model.WorkLog;
import com.DOCKin.worklog.model.WorkLogStatus;
import com.DOCKin.worklog.repository.WorkLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 작업일지 승인·반려 (P2-17-1). {@code AbsenceRequestService}의 승인·거절을 그대로 옮겼다.
 *
 * <p>{@code WorkLogsService}에 넣지 않은 이유: 그쪽은 작성자의 CRUD고 여기는 관리자의 결정이다.
 * 권한 검사의 방향이 반대(작성자 본인 vs 같은 구역 ADMIN)라 한 클래스에 두면 어느 검사가
 * 어느 메서드에 붙는지 읽는 사람이 매번 따져야 한다.
 *
 * <h3>같은 구역의 ADMIN만</h3>
 * 작업일지 목록·검색이 이미 "같은 {@code ship_yard_area}"로 제한돼 있다(P2-18-10). 볼 수 없는 글을
 * 승인할 수 있으면 범위가 둘이 된다. 휴가 승인은 구역 검사가 없는데, 그쪽은 목록도 전체라 일관된다.
 * {@code SecurityConfig}가 {@code /api/*}{@code /admin/**}를 ADMIN으로 막지만 서비스에서도 한 번 더 본다
 * (P2-18-6 "두 겹으로 남긴다").
 *
 * <h3>PENDING만 결정할 수 있다</h3>
 * 승인된 글을 다시 반려하거나 그 반대는 409다. 다시 검토하려면 작성자가 수정해 PENDING으로
 * 돌려야 한다({@link WorkLog#resetReview}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkLogReviewService {

    private final WorkLogRepository workLogRepository;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** 관리자 검토 목록 — 같은 구역, 상태 하나(기본 PENDING). 커서 규칙은 작성자 목록과 같다. */
    public Slice<WorkLogDto> list(String adminUserId, WorkLogStatus status, WorkLogCursor before, Pageable pageable) {
        Member admin = requireAdmin(adminUserId);
        List<Member> areaMembers = memberRepository.findByShipYardArea(admin.getShipYardArea());
        WorkLogStatus wanted = status == null ? WorkLogStatus.PENDING : status;
        int page = before == null ? pageable.getPageNumber() : 0;
        return workLogRepository.findByMemberInAndStatus(areaMembers, wanted,
                        before == null ? null : before.createdAt(),
                        before == null ? null : before.logId(),
                        PageRequest.of(page, pageable.getPageSize()))
                .map(WorkLogDto::from);
    }

    @Transactional
    public WorkLogDto approve(String adminUserId, Long logId, String comment) {
        Member admin = requireAdmin(adminUserId);
        WorkLog log = requirePendingInArea(logId, admin);

        log.approve(admin, blankToNull(comment), LocalDateTime.now(clock));

        eventPublisher.publishEvent(new WorkLogReviewed(
                log.getLogId(), log.getMember().getUserId(), WorkLogStatus.APPROVED,
                admin.getUserId(), log.getReviewComment()));
        return WorkLogDto.from(log);
    }

    @Transactional
    public WorkLogDto reject(String adminUserId, Long logId, String comment) {
        Member admin = requireAdmin(adminUserId);
        WorkLog log = requirePendingInArea(logId, admin);

        log.reject(admin, comment, LocalDateTime.now(clock));

        eventPublisher.publishEvent(new WorkLogReviewed(
                log.getLogId(), log.getMember().getUserId(), WorkLogStatus.REJECTED,
                admin.getUserId(), comment));
        return WorkLogDto.from(log);
    }

    private Member requireAdmin(String userId) {
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (member.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return member;
    }

    /** 구역 검사가 상태 검사보다 먼저다 — 다른 구역의 글은 검토됐는지조차 알려주지 않는다. */
    private WorkLog requirePendingInArea(Long logId, Member admin) {
        WorkLog log = workLogRepository.findById(logId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOG_NOT_FOUND));
        if (!log.getMember().getShipYardArea().equals(admin.getShipYardArea())) {
            throw new BusinessException(ErrorCode.WORKLOG_REVIEW_AREA);
        }
        if (!log.isPending()) {
            throw new BusinessException(ErrorCode.WORKLOG_ALREADY_REVIEWED);
        }
        return log;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
