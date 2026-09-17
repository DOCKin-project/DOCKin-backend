package com.DOCKin.worklog.service;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.worklog.dto.WorkLogDto;
import com.DOCKin.worklog.dto.WorkLogsUpdateRequestDto;
import com.DOCKin.worklog.model.WorkLogStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 승인·반려 루프 중 <b>DB를 거쳐야 보이는 것</b> (P2-17-1). 규칙 자체는 {@code WorkLogReviewServiceTest}.
 *
 * <ul>
 *   <li>{@code ?status=} 필터 — null이면 전부, 값이 있으면 그 상태만. {@code CAST(:status AS String)}이 없으면
 *       null에서 "could not determine data type of parameter"다({@code beforeCreatedAt}이 2026-09-15에 겪은 것).
 *       이 테스트가 그 캐스트를 지키는 장치다.</li>
 *   <li>수정 시 되돌림 — 엔티티 메서드가 아니라 {@code updateWorklog} 경로를 실제로 타서 행에 반영되는지.</li>
 *   <li>경로 규칙 — {@code /api/work-logs/admin/**}가 {@code SecurityConfig}의 {@code /api/*}{@code /admin/**}에
 *       걸리는지. 서비스 검사는 두 번째 겹이라 첫 겹이 실제로 있는지는 여기서만 보인다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkLogReviewFlowTest extends ContainerTestSupport {

    private static final String PREFIX = "wlrv";
    private static final String AREA = "승인루프구역";
    private static final String ADMIN = PREFIX + "-admin";
    private static final String AUTHOR = PREFIX + "-author";

    @Autowired private WorkLogsService workLogsService;
    @Autowired private WorkLogReviewService reviewService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;

    private Long pendingId;
    private Long approvedId;
    private Long rejectedId;

    @BeforeEach
    void seed() {
        cleanup();
        insertUser(ADMIN, "ADMIN");
        insertUser(AUTHOR, "USER");
        LocalDateTime base = LocalDateTime.of(2026, 9, 18, 9, 0);
        pendingId = insertWorkLog("p", base.plusMinutes(3));
        approvedId = insertWorkLog("a", base.plusMinutes(2));
        rejectedId = insertWorkLog("r", base.plusMinutes(1));
        reviewService.approve(ADMIN, approvedId, "ok");
        reviewService.reject(ADMIN, rejectedId, "사진 없음");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM work_logs WHERE user_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM users WHERE user_id LIKE ?", PREFIX + "%");
    }

    @Test
    @DisplayName("status가 없으면 전부, 있으면 그 상태만 — null 바인딩이 PostgreSQL을 통과한다")
    void statusFilter() {
        assertEquals(List.of(pendingId, approvedId, rejectedId), ids(null));
        assertEquals(List.of(pendingId), ids(WorkLogStatus.PENDING));
        assertEquals(List.of(approvedId), ids(WorkLogStatus.APPROVED));
        assertEquals(List.of(rejectedId), ids(WorkLogStatus.REJECTED));
    }

    @Test
    @DisplayName("반려된 일지를 작성자가 고치면 PENDING으로 돌아가고 검토 필드 셋이 행에서 지워진다")
    void editResetsReview() {
        Map<String, Object> before = row(rejectedId);
        assertEquals("REJECTED", before.get("status"));
        assertEquals(ADMIN, before.get("reviewed_by"));

        WorkLogDto dto = workLogsService.updateWorklog(AUTHOR, rejectedId,
                WorkLogsUpdateRequestDto.builder().title("고쳤다").build(), null);

        assertEquals(WorkLogStatus.PENDING, dto.getStatus());
        assertNull(dto.getReviewedBy());
        Map<String, Object> after = row(rejectedId);
        assertEquals("PENDING", after.get("status"));
        assertNull(after.get("reviewed_by"));
        assertNull(after.get("reviewed_at"));
        assertNull(after.get("review_comment"));

        // 되돌아왔으니 다시 결정할 수 있다 — 반려 → 고침 → 승인이 루프의 전부다.
        assertEquals(WorkLogStatus.APPROVED, reviewService.approve(ADMIN, rejectedId, null).getStatus());
    }

    @Test
    @DisplayName("익명은 401")
    void anonymous() throws Exception {
        mockMvc.perform(patch("/api/work-logs/admin/{id}/approve", pendingId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("일반 사용자는 경로 규칙이 403 — 서비스 검사보다 앞에서")
    void ordinaryUser() throws Exception {
        mockMvc.perform(patch("/api/work-logs/admin/{id}/approve", pendingId))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/work-logs/admin/{id}/reject", pendingId))
                .andExpect(status().isForbidden());
        assertEquals("PENDING", row(pendingId).get("status"));
    }

    private List<Long> ids(WorkLogStatus status) {
        Slice<WorkLogDto> page = workLogsService.readWorklog(AUTHOR, status, null, PageRequest.of(0, 20));
        return page.getContent().stream().map(WorkLogDto::getLogId).toList();
    }

    private Map<String, Object> row(Long logId) {
        return jdbc.queryForMap(
                "SELECT status, reviewed_by, reviewed_at, review_comment FROM work_logs WHERE log_id = ?", logId);
    }

    private Long insertWorkLog(String title, LocalDateTime at) {
        return jdbc.queryForObject("""
                INSERT INTO work_logs (title, log_text, created_at, updated_at, user_id)
                VALUES (?, ?, ?, ?, ?)
                RETURNING log_id
                """, Long.class, title, "본문", at, at, AUTHOR);
    }

    private void insertUser(String userId, String role) {
        jdbc.update("""
                INSERT INTO users (user_id, name, password, role, language_code,
                                   ship_yard_area, remaining_leave_days, tts_enabled, work_shift, created_at)
                VALUES (?, ?, ?, ?, 'ko', ?, 15, false, 'MORNING', ?)
                """, userId, userId.substring(0, 10), "$2a$10$" + "0".repeat(53), role, AREA,
                LocalDateTime.of(2026, 1, 1, 0, 0));
    }
}
