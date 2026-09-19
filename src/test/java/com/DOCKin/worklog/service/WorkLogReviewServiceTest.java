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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 승인·반려의 규칙 (P2-17-1). {@code AbsenceRequestServiceTest}와 같은 꼴 — 저장소는 목이고 상태 전이만 본다.
 * DB를 거치는 쪽(필터의 null 바인딩, 수정 시 되돌림)은 {@code WorkLogReviewFlowTest}.
 */
@ExtendWith(MockitoExtension.class)
class WorkLogReviewServiceTest {

    @Mock
    private WorkLogRepository workLogsRepository;
    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private WorkLogReviewService service;

    private static final String ADMIN_ID = "admin1";
    private static final String USER_ID = "user1";

    private Member admin() {
        return Member.builder().userId(ADMIN_ID).role(UserRole.ADMIN).build();
    }

    private Member author() {
        return Member.builder().userId(USER_ID).role(UserRole.USER).build();
    }

    private WorkLog log(WorkLogStatus status) {
        return WorkLog.builder().logId(1L).title("t").logText("x").member(author()).status(status).build();
    }

    @Test
    @DisplayName("승인하면 APPROVED, 검토자·시각·코멘트가 함께 채워진다")
    void approve() {
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        WorkLog log = log(WorkLogStatus.PENDING);
        when(workLogsRepository.findById(1L)).thenReturn(Optional.of(log));

        WorkLogDto dto = service.approve(ADMIN_ID, 1L, "좋습니다");

        assertEquals(WorkLogStatus.APPROVED, dto.getStatus());
        assertEquals(ADMIN_ID, dto.getReviewedBy());
        assertNotNull(dto.getReviewedAt());
        assertEquals("좋습니다", dto.getReviewComment());
        assertSame(log.getReviewedBy().getUserId(), ADMIN_ID);
    }

    @Test
    @DisplayName("반려하면 REJECTED — 코멘트가 없어도 된다")
    void reject_withoutComment() {
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(workLogsRepository.findById(1L)).thenReturn(Optional.of(log(WorkLogStatus.PENDING)));

        WorkLogDto dto = service.reject(ADMIN_ID, 1L, null);

        assertEquals(WorkLogStatus.REJECTED, dto.getStatus());
        assertNull(dto.getReviewComment());
    }

    @Test
    @DisplayName("PENDING이 아니면 409 — 승인된 것을 반려하거나 반려된 것을 승인할 수 없다")
    void alreadyReviewed() {
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(workLogsRepository.findById(1L)).thenReturn(Optional.of(log(WorkLogStatus.APPROVED)));

        BusinessException e = assertThrows(BusinessException.class, () -> service.reject(ADMIN_ID, 1L, null));
        assertEquals(ErrorCode.LOG_ALREADY_REVIEWED, e.getErrorCode());

        when(workLogsRepository.findById(2L)).thenReturn(Optional.of(log(WorkLogStatus.REJECTED)));
        e = assertThrows(BusinessException.class, () -> service.approve(ADMIN_ID, 2L, null));
        assertEquals(ErrorCode.LOG_ALREADY_REVIEWED, e.getErrorCode());
    }

    @Test
    @DisplayName("관리자가 아니면 403 — 작업일지를 조회하기 전에 거부한다")
    void notAdmin() {
        when(memberRepository.findByUserId(USER_ID)).thenReturn(Optional.of(author()));

        BusinessException e = assertThrows(BusinessException.class, () -> service.approve(USER_ID, 1L, null));
        assertEquals(ErrorCode.NOT_LOG_REVIEWER, e.getErrorCode());
    }

    @Test
    @DisplayName("없는 작업일지는 404")
    void notFound() {
        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(workLogsRepository.findById(9L)).thenReturn(Optional.empty());

        BusinessException e = assertThrows(BusinessException.class, () -> service.approve(ADMIN_ID, 9L, null));
        assertEquals(ErrorCode.LOG_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("수정하면 PENDING으로 돌아가고 검토 필드 셋이 비워진다 — 그래서 다시 결정할 수 있다")
    void resetReview_thenDecideAgain() {
        WorkLog log = log(WorkLogStatus.PENDING);
        log.reject(admin(), "사진이 없다");
        assertEquals(WorkLogStatus.REJECTED, log.getStatus());

        log.resetReview();

        assertEquals(WorkLogStatus.PENDING, log.getStatus());
        assertNull(log.getReviewedBy());
        assertNull(log.getReviewedAt());
        assertNull(log.getReviewComment());

        when(memberRepository.findByUserId(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(workLogsRepository.findById(1L)).thenReturn(Optional.of(log));
        assertEquals(WorkLogStatus.APPROVED, service.approve(ADMIN_ID, 1L, null).getStatus());
    }
}
