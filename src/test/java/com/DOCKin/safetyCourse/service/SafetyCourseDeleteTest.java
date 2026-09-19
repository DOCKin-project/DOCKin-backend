package com.DOCKin.safetyCourse.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.safetyCourse.dto.SafetyCourseUpdateRequestDto;
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
 * 검증: 안전교육 삭제가 <b>수강 기록이 있으면 409, 없으면 ADMIN 누구나</b> (#107).
 *
 * <p>전에는 삭제만 "작성자 본인"(수정은 ADMIN 누구나)이라 작성자가 퇴사하면 못 지웠고, 수강 기록이 있으면
 * FK(NO ACTION)에 걸려 500이었다. 여기서는 행을 직접 넣고 서비스로 지운다 — 409가 FK 위반보다 먼저인지가 핵심.
 */
@SpringBootTest
@DisplayName("안전교육 삭제 - 수강 기록 있으면 409, 없으면 작성자 아닌 ADMIN도 (#107)")
class SafetyCourseDeleteTest extends ContainerTestSupport {

    private static final String CREATOR = "sc-creator";
    private static final String OTHER_ADMIN = "sc-admin2";
    private static final String WORKER = "sc-worker";

    @Autowired
    private SafetyCourseService service;
    @Autowired
    private JdbcClient jdbc;

    private Integer watchedCourse;
    private Integer freshCourse;

    @BeforeEach
    void data() {
        for (String[] u : new String[][]{{CREATOR, "ADMIN"}, {OTHER_ADMIN, "ADMIN"}, {WORKER, "USER"}}) {
            jdbc.sql("""
                    INSERT INTO users (user_id, created_at, language_code, name, password,
                                       remaining_leave_days, role, ship_yard_area, tts_enabled)
                    VALUES (:u, now(), 'ko', 'sc', 'x', 15, :r, 'A', false)
                    ON CONFLICT (user_id) DO NOTHING
                    """).param("u", u[0]).param("r", u[1]).update();
        }
        watchedCourse = insertCourse("본 교육");
        freshCourse = insertCourse("아무도 안 본 교육");
        jdbc.sql("""
                INSERT INTO safety_enrollments (status, enrolled_at, completion_date, course_id, user_id)
                VALUES ('WATCHED', now(), now(), :c, :u)
                """).param("c", watchedCourse).param("u", WORKER).update();
    }

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM safety_enrollments WHERE user_id = :u").param("u", WORKER).update();
        jdbc.sql("DELETE FROM safety_courses WHERE created_by = :u").param("u", CREATOR).update();
        jdbc.sql("DELETE FROM users WHERE user_id IN (:a, :b, :c)")
                .param("a", CREATOR).param("b", OTHER_ADMIN).param("c", WORKER).update();
    }

    @Test
    @DisplayName("수강 기록이 있으면 409 S003 - FK 위반 500이 아니고, 교육과 기록은 그대로")
    void watchedCourseIs409() {
        assertThatThrownBy(() -> service.deleteSafetyCourse(CREATOR, watchedCourse))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.SAFETYCOURSE_HAS_ENROLLMENTS);

        assertThat(count("safety_courses", "course_id = " + watchedCourse)).isEqualTo(1);
        assertThat(count("safety_enrollments", "course_id = " + watchedCourse)).isEqualTo(1);
    }

    @Test
    @DisplayName("작성자가 아닌 ADMIN도 지운다 - 수정과 같은 기준. 작성자가 퇴사해도 지울 수 있다")
    void otherAdminCanDelete() {
        service.deleteSafetyCourse(OTHER_ADMIN, freshCourse);

        assertThat(count("safety_courses", "course_id = " + freshCourse)).isZero();
    }

    @Test
    @DisplayName("USER는 403 - 경로 규칙(/api/*/admin/**)이 먼저 막지만 서비스도 한 겹 더")
    void workerIs403() {
        assertThatThrownBy(() -> service.deleteSafetyCourse(WORKER, freshCourse))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.SAFETYCOURSE_AUTHOR);
        assertThat(count("safety_courses", "course_id = " + freshCourse)).isEqualTo(1);
    }

    @Test
    @DisplayName("수정 - materialUrl도 갱신된다. DTO에는 있는데 옮기지 않고 있었다")
    void reviseUpdatesMaterialUrl() {
        SafetyCourseUpdateRequestDto dto = new SafetyCourseUpdateRequestDto();
        dto.setMaterialUrl("https://s3/new-material.pdf");

        service.reviseSafetyCourse(dto, OTHER_ADMIN, freshCourse);

        assertThat(jdbc.sql("SELECT material_url FROM safety_courses WHERE course_id = :c")
                .param("c", freshCourse).query(String.class).single()).isEqualTo("https://s3/new-material.pdf");
        assertThat(jdbc.sql("SELECT title FROM safety_courses WHERE course_id = :c")
                .param("c", freshCourse).query(String.class).single()).as("안 준 필드는 그대로").isEqualTo("아무도 안 본 교육");
    }

    private Integer insertCourse(String title) {
        return jdbc.sql("""
                INSERT INTO safety_courses (title, description, video_url, material_url, duration_minutes, created_by, created_at)
                VALUES (:t, 'd', 'https://v', 'https://m', 10, :u, now()) RETURNING course_id
                """).param("t", title).param("u", CREATOR).query(Integer.class).single();
    }

    private int count(String table, String where) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + where).query(Integer.class).single();
    }
}
