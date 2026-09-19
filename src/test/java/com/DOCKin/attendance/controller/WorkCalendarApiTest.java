package com.DOCKin.attendance.controller;

import com.DOCKin.attendance.service.WorkCalendarService;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.member.dto.CustomUserInfoDto;
import com.DOCKin.member.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검증: 근무일 캘린더 API가 경로 규칙·검증·DB까지 한 줄로 이어지는가 (P2-6-1).
 *
 * <p>단위 테스트({@code WorkCalendarServiceTest})는 서비스 규칙만 본다. 여기는 그 바깥 —
 * USER가 조회는 되고 등록은 403인지(경로 규칙), 일괄 upsert가 실제 행에 반영되는지,
 * 삭제 뒤 {@code isWorkingDay}가 기본 규칙으로 돌아오는지를 컨테이너 DB에서 본다.
 *
 * <p>2027년을 쓴다. 다른 테스트나 시드가 채울 리 없는 해라 행이 겹치지 않고, 끝나면 그 해만 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("근무일 캘린더 API - 조회는 전원, 등록·삭제는 관리자, 삭제하면 기본 규칙")
class WorkCalendarApiTest extends ContainerTestSupport {

    private static final String ADMIN = "cal-admin";
    private static final String WORKER = "cal-worker";

    /** 2027-03-01 월요일 — 삼일절 */
    private static final LocalDate MONDAY_HOLIDAY = LocalDate.of(2027, 3, 1);
    /** 2027-03-06 토요일 */
    private static final LocalDate SATURDAY = LocalDate.of(2027, 3, 6);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private WorkCalendarService workCalendarService;

    @BeforeEach
    void users() {
        for (String[] u : new String[][]{{ADMIN, "ADMIN"}, {WORKER, "USER"}}) {
            jdbc.sql("""
                    INSERT INTO users (user_id, created_at, language_code, name, password,
                                       remaining_leave_days, role, ship_yard_area, tts_enabled)
                    VALUES (:u, now(), 'ko', 'cal', 'x', 15, :r, 'A', false)
                    ON CONFLICT (user_id) DO NOTHING
                    """).param("u", u[0]).param("r", u[1]).update();
        }
    }

    @AfterEach
    void cleanCalendar() {
        jdbc.sql("DELETE FROM work_calendar WHERE calendar_date BETWEEN '2027-01-01' AND '2027-12-31'").update();
    }

    @Test
    @DisplayName("USER - 조회는 200, 등록은 403 (경로 규칙이 잡는다)")
    void userCanReadNotWrite() throws Exception {
        mockMvc.perform(get("/api/attendance/calendar").param("year", "2027")
                        .with(user(principal(WORKER, UserRole.USER))))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/attendance/admin/calendar/2027-03-01")
                        .with(user(principal(WORKER, UserRole.USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayType\":\"HOLIDAY\",\"description\":\"삼일절\"}"))
                .andExpect(status().isForbidden());

        assertThat(count(MONDAY_HOLIDAY)).isZero();
    }

    @Test
    @DisplayName("익명은 401 - 조회도 로그인은 해야 한다")
    void anonymous() throws Exception {
        mockMvc.perform(get("/api/attendance/calendar")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("일괄 upsert - 행에 반영되고, 같은 날짜 두 번이면 뒤가 이기며, 조회가 날짜순으로 돌려준다")
    void bulkUpsertReachesRows() throws Exception {
        mockMvc.perform(put("/api/attendance/admin/calendar")
                        .with(user(principal(ADMIN, UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entries":[
                                  {"date":"2027-03-06","dayType":"WORKDAY","description":"특근"},
                                  {"date":"2027-03-01","dayType":"WEEKEND","description":"오타"},
                                  {"date":"2027-03-01","dayType":"HOLIDAY","description":"삼일절"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        assertThat(jdbc.sql("SELECT day_type FROM work_calendar WHERE calendar_date = :d")
                .param("d", MONDAY_HOLIDAY).query(String.class).single()).isEqualTo("HOLIDAY");
        assertThat(count(MONDAY_HOLIDAY)).isEqualTo(1);

        mockMvc.perform(get("/api/attendance/calendar").param("year", "2027")
                        .with(user(principal(WORKER, UserRole.USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].date").value("2027-03-01"))
                .andExpect(jsonPath("$[0].workingDay").value(false))
                .andExpect(jsonPath("$[1].date").value("2027-03-06"))
                .andExpect(jsonPath("$[1].workingDay").value(true));
    }

    @Test
    @DisplayName("단건 PUT 두 번은 한 행 - 등록과 갱신이 같은 요청")
    void putIsIdempotent() throws Exception {
        for (String desc : new String[]{"임시", "삼일절"}) {
            mockMvc.perform(put("/api/attendance/admin/calendar/2027-03-01")
                            .with(user(principal(ADMIN, UserRole.ADMIN)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dayType\":\"HOLIDAY\",\"description\":\"" + desc + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.description").value(desc));
        }
        assertThat(count(MONDAY_HOLIDAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제하면 기본 규칙으로 - 특근 토요일을 지우면 다시 휴무")
    void deleteRestoresDefaultRule() throws Exception {
        mockMvc.perform(put("/api/attendance/admin/calendar/2027-03-06")
                        .with(user(principal(ADMIN, UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayType\":\"WORKDAY\",\"description\":\"특근\"}"))
                .andExpect(status().isOk());
        assertThat(workCalendarService.isWorkingDay(SATURDAY)).isTrue();

        mockMvc.perform(delete("/api/attendance/admin/calendar/2027-03-06")
                        .with(user(principal(ADMIN, UserRole.ADMIN))))
                .andExpect(status().isNoContent());

        assertThat(count(SATURDAY)).isZero();
        assertThat(workCalendarService.isWorkingDay(SATURDAY)).isFalse();

        mockMvc.perform(delete("/api/attendance/admin/calendar/2027-03-06")
                        .with(user(principal(ADMIN, UserRole.ADMIN))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("검증 - dayType 없음·설명 101자·entries 빈 배열·깨진 날짜는 400, DB에 닿지 않는다")
    void validation() throws Exception {
        mockMvc.perform(put("/api/attendance/admin/calendar/2027-03-01")
                        .with(user(principal(ADMIN, UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/attendance/admin/calendar/2027-03-01")
                        .with(user(principal(ADMIN, UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayType\":\"HOLIDAY\",\"description\":\"" + "가".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/attendance/admin/calendar")
                        .with(user(principal(ADMIN, UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"entries\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/attendance/admin/calendar/2027-13-01")
                        .with(user(principal(ADMIN, UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dayType\":\"HOLIDAY\"}"))
                .andExpect(status().isBadRequest());

        assertThat(count(MONDAY_HOLIDAY)).isZero();
    }

    private int count(LocalDate date) {
        return jdbc.sql("SELECT count(*) FROM work_calendar WHERE calendar_date = :d")
                .param("d", date).query(Integer.class).single();
    }

    private static CustomUserDetails principal(String userId, UserRole role) {
        return new CustomUserDetails(CustomUserInfoDto.builder()
                .userId(userId).name("cal").password("n/a").role(role).build());
    }
}
