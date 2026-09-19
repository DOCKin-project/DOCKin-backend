package com.DOCKin.worklog.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.worklog.dto.WorkLogDto;
import com.DOCKin.worklog.model.WorkLog;
import com.DOCKin.worklog.model.WorkLogStatus;
import com.DOCKin.worklog.repository.WorkLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업일지 승인·반려 (P2-17-1). {@code AbsenceRequestService.approveRequest/rejectRequest}를 옮겨 왔다.
 *
 * <p>{@code WorkLogsService}에 넣지 않은 이유는 그쪽이 이미 CRUD·목록·검색으로 250줄이고,
 * 이 둘은 관리자 경로에서만 불리기 때문이다. 수정 시 {@code PENDING}으로 되돌리는 쪽은
 * 작성자 경로라 {@code WorkLogsService.updateWorklog}에 있다.
 *
 * <p>휴가와 다른 것 둘.
 * <ul>
 *   <li>이벤트를 내지 않는다. 휴가 승인은 근태가 같은 트랜잭션에서 받아야 했지만, 여기는 받을 쪽이
 *       없다. FCM이 오면 {@code AFTER_COMMIT}으로 붙는 자리다(P2-12-6).</li>
 *   <li>승인·반려 뒤에도 끝이 아니다 — 작성자가 고치면 다시 {@code PENDING}이고 다시 결정할 수 있다.
 *       그래서 "이미 검토됨" 409는 <b>지금</b> PENDING이 아닐 때만이다.</li>
 * </ul>
 *
 * <p>ADMIN 검사는 {@code SecurityConfig}의 {@code /api/*}{@code /admin/**} 규칙과 여기, 두 겹이다
 * (P2-18-6 — 경로 규칙이 한 겹, 서비스가 한 겹). 관리자의 구역은 보지 않는다 — 휴가와 같다.
 */
@Service
@RequiredArgsConstructor
public class WorkLogReviewService {

    private final WorkLogRepository workLogsRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public WorkLogDto approve(String adminUserId, Long logId, String comment) {
        Member admin = requireAdmin(adminUserId);
        WorkLog log = requirePending(logId);
        log.approve(admin, comment);
        return WorkLogDto.from(log);
    }

    @Transactional
    public WorkLogDto reject(String adminUserId, Long logId, String comment) {
        Member admin = requireAdmin(adminUserId);
        WorkLog log = requirePending(logId);
        log.reject(admin, comment);
        return WorkLogDto.from(log);
    }

    private Member requireAdmin(String userId) {
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (member.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.NOT_LOG_REVIEWER);
        }
        return member;
    }

    private WorkLog requirePending(Long logId) {
        WorkLog log = workLogsRepository.findById(logId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOG_NOT_FOUND));
        if (log.getStatus() != WorkLogStatus.PENDING) {
            throw new BusinessException(ErrorCode.LOG_ALREADY_REVIEWED);
        }
        return log;
    }
}
