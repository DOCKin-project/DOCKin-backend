package com.DOCKin.worklog.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.worklog.dto.WorkLogDto;
import com.DOCKin.worklog.event.WorkLogReviewed;
import com.DOCKin.worklog.model.WorkLog;
import com.DOCKin.worklog.model.WorkLogStatus;
import com.DOCKin.worklog.repository.WorkLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 검증: 승인·반려는 같은 구역 ADMIN이 PENDING에만 할 수 있고, 결과가 엔티티와 이벤트에 같이 남는다 (P2-17-1).
 * 수정하면 PENDING으로 돌아가는 것은 {@link WorkLog#resetReview}를 직접 본다 — {@code updateWorklog}는
 * S3·장비까지 끼어 있어 그 한 줄을 보려고 전부 목으로 세우는 것이 과하다.
 */
@ExtendWith(MockitoExtension.class)
class WorkLogReviewServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 14, 0);

    @Mock
    private WorkLogRepository workLogRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private WorkLogReviewService service() {
        Clock fixed = Clock.fixed(NOW.atZone(SEOUL).toInstant(), SEOUL);
        return new WorkLogReviewService(workLogRepository, memberRepository, eventPublisher, fixed);
    }

    private static Member member(String id, UserRole role, String area) {
        return Member.builder().userId(id).role(role).shipYardArea(area).build();
    }

    private static WorkLog pendingLog(Member author) {
        return WorkLog.builder().logId(7L).title("제목").logText("본문").member(author).build();
    }

    @Test
    @DisplayName("승인: 상태·검토자·시각·코멘트가 남고 APPROVED 이벤트가 나간다")
    void approve() {
        Member admin = member("admin1", UserRole.ADMIN, "A");
        WorkLog log = pendingLog(member("user1", UserRole.USER, "A"));
        when(memberRepository.findByUserId("admin1")).thenReturn(Optional.of(admin));
        when(workLogRepository.findById(7L)).thenReturn(Optional.of(log));

        WorkLogDto dto = service().approve("admin1", 7L, "확인함");

        assertThat(log.getStatus()).isEqualTo(WorkLogStatus.APPROVED);
        assertThat(log.getReviewedBy()).isSameAs(admin);
        assertThat(log.getReviewedAt()).isEqualTo(NOW);
        assertThat(log.getReviewComment()).isEqualTo("확인함");
        assertThat(dto.getStatus()).isEqualTo(WorkLogStatus.APPROVED);
        assertThat(dto.getReviewedBy()).isEqualTo("admin1");

        ArgumentCaptor<WorkLogReviewed> event = ArgumentCaptor.forClass(WorkLogReviewed.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new WorkLogReviewed(7L, "user1", WorkLogStatus.APPROVED, "admin1", "확인함"));
    }

    @Test
    @DisplayName("승인 코멘트가 빈 문자열이면 null로 남는다 - 본문 없는 승인과 같아야 한다")
    void approveBlankComment() {
        Member admin = member("admin1", UserRole.ADMIN, "A");
        WorkLog log = pendingLog(member("user1", UserRole.USER, "A"));
        when(memberRepository.findByUserId("admin1")).thenReturn(Optional.of(admin));
        when(workLogRepository.findById(7L)).thenReturn(Optional.of(log));

        service().approve("admin1", 7L, "  ");

        assertThat(log.getReviewComment()).isNull();
    }

    @Test
    @DisplayName("반려: REJECTED와 사유가 남고 REJECTED 이벤트가 나간다")
    void reject() {
        Member admin = member("admin1", UserRole.ADMIN, "A");
        WorkLog log = pendingLog(member("user1", UserRole.USER, "A"));
        when(memberRepository.findByUserId("admin1")).thenReturn(Optional.of(admin));
        when(workLogRepository.findById(7L)).thenReturn(Optional.of(log));

        service().reject("admin1", 7L, "사진이 없다");

        assertThat(log.getStatus()).isEqualTo(WorkLogStatus.REJECTED);
        assertThat(log.getReviewComment()).isEqualTo("사진이 없다");
        verify(eventPublisher).publishEvent(new WorkLogReviewed(7L, "user1", WorkLogStatus.REJECTED, "admin1", "사진이 없다"));
    }

    @Test
    @DisplayName("다른 구역의 작업일지는 403 - 목록에서 볼 수 없는 글을 승인할 수 없다")
    void otherArea() {
        Member admin = member("admin1", UserRole.ADMIN, "A");
        WorkLog log = pendingLog(member("user1", UserRole.USER, "B"));
        when(memberRepository.findByUserId("admin1")).thenReturn(Optional.of(admin));
        when(workLogRepository.findById(7L)).thenReturn(Optional.of(log));

        assertThatThrownBy(() -> service().approve("admin1", 7L, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WORKLOG_REVIEW_AREA);
        assertThat(log.getStatus()).isEqualTo(WorkLogStatus.PENDING);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이미 검토된 글은 409 - 다시 검토하려면 작성자가 수정해야 한다")
    void alreadyReviewed() {
        Member admin = member("admin1", UserRole.ADMIN, "A");
        WorkLog log = pendingLog(member("user1", UserRole.USER, "A"));
        log.approve(admin, null, NOW.minusDays(1));
        when(memberRepository.findByUserId("admin1")).thenReturn(Optional.of(admin));
        when(workLogRepository.findById(7L)).thenReturn(Optional.of(log));

        assertThatThrownBy(() -> service().reject("admin1", 7L, "늦었다"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WORKLOG_ALREADY_REVIEWED);
        assertThat(log.getStatus()).isEqualTo(WorkLogStatus.APPROVED);
    }

    @Test
    @DisplayName("USER는 403 - SecurityConfig가 막지만 서비스도 한 번 더 본다 (P2-18-6)")
    void notAdmin() {
        when(memberRepository.findByUserId("user9")).thenReturn(Optional.of(member("user9", UserRole.USER, "A")));

        assertThatThrownBy(() -> service().approve("user9", 7L, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCESS_DENIED);
        verify(workLogRepository, never()).findById(any());
    }

    @Test
    @DisplayName("수정하면 PENDING으로 돌아가고 검토 흔적이 지워진다 - 승인된 글을 몰래 바꿀 수 없다")
    void resetOnEdit() {
        Member admin = member("admin1", UserRole.ADMIN, "A");
        WorkLog log = pendingLog(member("user1", UserRole.USER, "A"));
        log.reject(admin, "사진 없음", NOW);

        log.resetReview();

        assertThat(log.getStatus()).isEqualTo(WorkLogStatus.PENDING);
        assertThat(log.getReviewedBy()).isNull();
        assertThat(log.getReviewedAt()).isNull();
        assertThat(log.getReviewComment()).isNull();
    }
}
