package com.DOCKin.worklog.service;

import com.DOCKin.ai.dto.TranslateDomain;
import com.DOCKin.ai.service.FastApiService;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.worklog.dto.CommentCreateRequestDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검증: 단건 {@code logId} 경로(번역·댓글)가 목록과 같은 경계 — <b>같은 구역</b> — 를 지키는가 (#99).
 *
 * <p>P2-18-10이 목록·타인 조회·검색을 같은 구역으로 맞췄는데 번역은 {@code findById}, 댓글은 {@code existsById}만
 * 해서 인증만 있으면 아무 logId로 남의 구역 일지를 번역문·코멘트로 받아 볼 수 있었다.
 * 이제 셋 다 {@code WorkLogsService.requireVisible}를 거친다.
 *
 * <p>번역의 부정 경로는 FastAPI가 없어도 검증된다 — 검사가 FastAPI 호출 <b>앞</b>이라 403이 먼저다. 검사가 빠지면
 * 여기서 나오는 것은 403이 아니라 {@code localhost:9999} 연결 실패다. 긍정 경로(같은 구역이 번역해 받는 것)는
 * {@code WorkLogTranslateCacheTest}가 스텁 FastAPI로 본다.
 */
@SpringBootTest
@DisplayName("작업일지 단건 경로 가시성 - 번역·댓글도 같은 구역만 (#99)")
class WorkLogVisibilityTest extends ContainerTestSupport {

    private static final String AUTHOR_A = "vis-a1";
    private static final String ADMIN_A = "vis-a2";
    private static final String ADMIN_B = "vis-b1";

    @Autowired
    private WorkLogsService workLogsService;
    @Autowired
    private CommentService commentService;
    @Autowired
    private FastApiService fastApiService;
    @Autowired
    private JdbcClient jdbc;

    private Long logId;

    @BeforeEach
    void data() {
        for (String[] u : new String[][]{{AUTHOR_A, "USER", "A"}, {ADMIN_A, "ADMIN", "A"}, {ADMIN_B, "ADMIN", "B"}}) {
            jdbc.sql("""
                    INSERT INTO users (user_id, created_at, language_code, name, password,
                                       remaining_leave_days, role, ship_yard_area, tts_enabled)
                    VALUES (:u, now(), 'ko', 'vis', 'x', 15, :r, :a, false)
                    ON CONFLICT (user_id) DO NOTHING
                    """).param("u", u[0]).param("r", u[1]).param("a", u[2]).update();
        }
        logId = jdbc.sql("""
                INSERT INTO work_logs (title, log_text, created_at, updated_at, user_id)
                VALUES ('A 구역 일지', '본문', now(), now(), :u) RETURNING log_id
                """).param("u", AUTHOR_A).query(Long.class).single();
    }

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM work_log_comments WHERE log_id = :l").param("l", logId).update();
        jdbc.sql("DELETE FROM work_logs WHERE log_id = :l").param("l", logId).update();
        jdbc.sql("DELETE FROM users WHERE user_id IN (:a, :b, :c)")
                .param("a", AUTHOR_A).param("b", ADMIN_A).param("c", ADMIN_B).update();
    }

    @Test
    @DisplayName("requireVisible - 같은 구역은 일지를 돌려주고, 다른 구역은 403, 없는 일지는 404")
    void requireVisible() {
        assertThat(workLogsService.requireVisible(logId, ADMIN_A).getTitle()).isEqualTo("A 구역 일지");
        assertThat(workLogsService.requireVisible(logId, AUTHOR_A).getLogId()).isEqualTo(logId);

        assertThatThrownBy(() -> workLogsService.requireVisible(logId, ADMIN_B))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
        assertThatThrownBy(() -> workLogsService.requireVisible(logId + 1_000_000, ADMIN_A))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.LOG_NOT_FOUND);
    }

    @Test
    @DisplayName("번역 - 다른 구역은 FastAPI에 닿기 전에 403. 전엔 findById뿐이라 아무 logId나 번역해 줬다")
    void translateOtherArea() {
        assertThatThrownBy(() -> fastApiService.saveTranslateLog(
                logId, new TranslateDomain.Request("ko", "en", "vis-trace"), ADMIN_B))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
        assertThat(jdbc.sql("SELECT count(*) FROM work_log_translations WHERE log_id = :l")
                .param("l", logId).query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("댓글 - 다른 구역 관리자는 읽지도 달지도 못한다(403), 같은 구역 관리자는 둘 다 된다")
    void comments() {
        assertThatThrownBy(() -> commentService.readComment(logId, ADMIN_B))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
        assertThatThrownBy(() -> commentService.createComment(logId, ADMIN_B, comment("B에서")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);

        commentService.createComment(logId, ADMIN_A, comment("A에서"));
        assertThat(commentService.readComment(logId, ADMIN_A)).hasSize(1);
        assertThat(commentService.readComment(logId, AUTHOR_A)).as("작성자도 자기 구역이라 본다").hasSize(1);
    }

    private static CommentCreateRequestDto comment(String content) {
        CommentCreateRequestDto dto = new CommentCreateRequestDto();
        dto.setContent(content);
        return dto;
    }
}
