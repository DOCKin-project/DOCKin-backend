package com.DOCKin.safetyCourse.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.safetyCourse.dto.SafetyWatchStatusRequestDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 안전교육 시청 완료가 멱등인가 (#141).
 *
 * <p>예전 "찾고-없으면-만들기"는 동시 요청 둘이 둘 다 "없음"을 보고 둘 다 INSERT해 행이 둘이 됐고, 그 뒤 단건 조회가 500이었다.
 * V19의 유니크 + {@code INSERT … ON CONFLICT}로 한 행에 수렴해야 한다. 옛 코드에 V19 제약만 걸면 동시 요청 중 일부가
 * {@code DataIntegrityViolationException}으로 실패하는 것을 확인했다 — 제약만으로는 부족하고 기록 방식도 바뀌어야 했다.
 */
@SpringBootTest
@DisplayName("안전교육 완료 - 동시에 여러 번 보내도 204, 행은 하나 (#141)")
class SafetyEnrollmentCompleteTest extends ContainerTestSupport {

    private static final String CREATOR = "se-admin";
    private static final String WORKER = "se-worker";
    private static final int CONCURRENT = 8;

    @Autowired private SafetyTrainingService service;
    @Autowired private JdbcClient jdbc;

    private Integer courseId;

    @BeforeEach
    void data() {
        clean();
        for (String[] u : new String[][]{{CREATOR, "ADMIN"}, {WORKER, "USER"}}) {
            jdbc.sql("""
                    INSERT INTO users (user_id, created_at, language_code, name, password,
                                       remaining_leave_days, role, ship_yard_area, tts_enabled)
                    VALUES (:u, now(), 'ko', 'se', 'x', 15, :r, 'A', false)
                    """).param("u", u[0]).param("r", u[1]).update();
        }
        courseId = jdbc.sql("""
                INSERT INTO safety_courses (title, description, video_url, material_url, duration_minutes, created_by, created_at)
                VALUES ('밀폐공간', 'd', 'https://v', 'https://m', 10, :u, now()) RETURNING course_id
                """).param("u", CREATOR).query(Integer.class).single();
    }

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM safety_enrollments WHERE user_id = :u").param("u", WORKER).update();
        jdbc.sql("DELETE FROM safety_courses WHERE created_by = :u").param("u", CREATOR).update();
        jdbc.sql("DELETE FROM users WHERE user_id IN (:a, :b)").param("a", CREATOR).param("b", WORKER).update();
    }

    @Test
    @DisplayName("같은 교육 완료를 8번 동시에 보내도 전부 성공하고 WATCHED 행은 하나")
    void concurrentCompletesConverge() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENT; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                service.completedViewVideo(WORKER, request(courseId));
                return null;
            }));
        }
        start.countDown();

        List<String> failures = new ArrayList<>();
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                failures.add(String.valueOf(e.getCause() != null ? e.getCause() : e));
            }
        }
        pool.shutdownNow();

        assertThat(failures).isEmpty();
        assertThat(rows()).isEqualTo(1);
        assertThat(status()).isEqualTo("WATCHED");
        // 단건 조회가 다시 500이 나지 않는다 - 행이 둘이면 IncorrectResultSizeDataAccessException이었다.
        assertThat(service.searchUnwatchedVideo(WORKER)).noneMatch(c -> c.getCourseId().equals(courseId));
    }

    @Test
    @DisplayName("순서대로 다시 보내도 행은 하나 - 예전과 같은 멱등")
    void sequentialRepeatIsIdempotent() {
        service.completedViewVideo(WORKER, request(courseId));
        service.completedViewVideo(WORKER, request(courseId));

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 교육은 FK 위반 500이 아니라 404(SAFETYCOURSE_NOT_FOUND)")
    void unknownCourseIs404() {
        assertThatThrownBy(() -> service.completedViewVideo(WORKER, request(-1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.SAFETYCOURSE_NOT_FOUND);
        assertThat(rows()).isZero();
    }

    private static SafetyWatchStatusRequestDto request(Integer courseId) {
        SafetyWatchStatusRequestDto dto = new SafetyWatchStatusRequestDto();
        // 요청 DTO에 세터가 없다(역직렬화 전용).
        ReflectionTestUtils.setField(dto, "courseId", courseId);
        return dto;
    }

    private int rows() {
        return jdbc.sql("SELECT count(*) FROM safety_enrollments WHERE user_id = :u")
                .param("u", WORKER).query(Integer.class).single();
    }

    private String status() {
        return jdbc.sql("SELECT status FROM safety_enrollments WHERE user_id = :u")
                .param("u", WORKER).query(String.class).single();
    }
}
