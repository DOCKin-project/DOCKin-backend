package com.DOCKin.attendance.service;

import com.DOCKin.attendance.dto.AttendanceDailySummaryDto;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.member.model.WorkShift;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 하루 인원 집계 (P2-17-4). 왼쪽 조인 집계는 DB를 거쳐야 보인다 — 근태 행이 없는 사람이 {@code headcount}에
 * 들어가는지, 다른 구역·다른 날의 행이 새지 않는지, 구역이 비면 SUM의 NULL이 0으로 오는지.
 *
 * <p>표본: 구역 인원 6명(관리자 포함). 출근만 2(그중 1은 지각), 출퇴근 1(오후조), 휴가 1, 병결 1, 아무 행 없음 1.
 * 다른 구역 1명과 전날 행 1개를 섞어 둔다 — 새면 숫자가 어긋난다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttendanceDailySummaryTest extends ContainerTestSupport {

    private static final String PREFIX = "dsum";
    private static final String AREA = "집계구역";
    private static final String OTHER_AREA = "집계타구역";
    private static final String ADMIN = PREFIX + "-admin";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 18);

    @Autowired private AttendanceService attendanceService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void seed() {
        cleanup();
        insertUser(ADMIN, "ADMIN", AREA);
        for (int i = 1; i <= 5; i++) insertUser(user(i), "USER", AREA);
        insertUser(PREFIX + "-other", "USER", OTHER_AREA);
        jdbc.update("UPDATE users SET work_shift = 'AFTERNOON' WHERE user_id = ?", user(2));

        LocalDateTime nine = DAY.atTime(9, 0);
        insertAttendance(ADMIN, DAY, "NORMAL", nine, null);                    // 출근만
        insertAttendance(user(1), DAY, "LATE", nine.plusMinutes(30), null);    // 출근만 (지각)
        insertAttendance(user(2), DAY, "NORMAL", nine, nine.plusHours(9));     // 출퇴근
        insertAttendance(user(3), DAY, "VACATION", null, null);
        insertAttendance(user(4), DAY, "SICK", null, null);
        // user(5)는 행이 없다 — 아직 안 찍은 사람
        insertAttendance(PREFIX + "-other", DAY, "NORMAL", nine, nine.plusHours(9)); // 다른 구역
        insertAttendance(user(5), DAY.minusDays(1), "ABSENT", null, null);         // 전날
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM attendance WHERE user_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM users WHERE user_id LIKE ?", PREFIX + "%");
    }

    @Test
    @DisplayName("구역 인원 6 / 출근 3 / 퇴근 1 / 지각 1 / 휴가 1 / 병결 1 / 결근 0 — 다른 구역·전날은 안 센다")
    void summary() {
        AttendanceDailySummaryDto s = attendanceService.getDailySummary(ADMIN, DAY, null, null);

        assertEquals(DAY, s.date());
        assertEquals(AREA, s.shipYardArea());
        assertNull(s.workShift());
        assertEquals(6, s.headcount());
        assertEquals(3, s.clockedIn());
        assertEquals(1, s.clockedOut());
        assertEquals(1, s.late());
        assertEquals(1, s.vacation());
        assertEquals(1, s.sick());
        assertEquals(0, s.absent());
    }

    @Test
    @DisplayName("근무조를 주면 그 조만 — 오전조 5명 중 출근 2, 퇴근 0(퇴근한 사람은 오후조)")
    void shiftFilter() {
        AttendanceDailySummaryDto morning = attendanceService.getDailySummary(ADMIN, DAY, null, WorkShift.MORNING);
        assertEquals(WorkShift.MORNING, morning.workShift());
        assertEquals(5, morning.headcount());
        assertEquals(2, morning.clockedIn());
        assertEquals(0, morning.clockedOut());

        AttendanceDailySummaryDto afternoon = attendanceService.getDailySummary(ADMIN, DAY, null, WorkShift.AFTERNOON);
        assertEquals(1, afternoon.headcount());
        assertEquals(1, afternoon.clockedOut());

        assertEquals(0, attendanceService.getDailySummary(ADMIN, DAY, null, WorkShift.NIGHT).headcount());
    }

    @Test
    @DisplayName("전날은 결근 1, 나머지 0 — 인원은 그대로 6")
    void previousDay() {
        AttendanceDailySummaryDto s = attendanceService.getDailySummary(ADMIN, DAY.minusDays(1), null, null);
        assertEquals(6, s.headcount());
        assertEquals(0, s.clockedIn());
        assertEquals(1, s.absent());
    }

    @Test
    @DisplayName("다른 구역을 지정하면 그 구역 — 관리자 구역에 묶이지 않는다")
    void otherArea() {
        AttendanceDailySummaryDto s = attendanceService.getDailySummary(ADMIN, DAY, OTHER_AREA, null);
        assertEquals(OTHER_AREA, s.shipYardArea());
        assertEquals(1, s.headcount());
        assertEquals(1, s.clockedIn());
        assertEquals(1, s.clockedOut());
    }

    @Test
    @DisplayName("사람이 없는 구역은 전부 0 — SUM의 NULL이 새지 않는다")
    void emptyArea() {
        AttendanceDailySummaryDto s = attendanceService.getDailySummary(ADMIN, DAY, "없는구역", null);
        assertEquals(0, s.headcount());
        assertEquals(0, s.clockedIn());
        assertEquals(0, s.vacation());
    }

    @Test
    @DisplayName("관리자가 아니면 서비스도 403 — 경로 규칙의 두 번째 겹")
    void notAdmin() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> attendanceService.getDailySummary(user(1), DAY, null, null));
        assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("일반 사용자는 경로 규칙이 403")
    void pathRule() throws Exception {
        mockMvc.perform(get("/api/attendance/admin/daily-summary")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("익명은 401")
    void anonymous() throws Exception {
        mockMvc.perform(get("/api/attendance/admin/daily-summary")).andExpect(status().isUnauthorized());
    }

    private static String user(int i) {
        return PREFIX + "-u" + i;
    }

    private void insertAttendance(String userId, LocalDate day, String status,
                                  LocalDateTime in, LocalDateTime out) {
        // work_shift(V10)는 판정 교대의 스냅샷 — 여기서는 사용자의 현재 교대를 그대로 복사한다
        jdbc.update("""
                INSERT INTO attendance (id, user_id, work_date, work_shift, status, clock_in_time, clock_out_time)
                SELECT nextval('attendance_seq'), user_id, ?, COALESCE(work_shift, 'MORNING'), ?, ?, ?
                  FROM users WHERE user_id = ?
                """, day, status, in, out, userId);
    }

    private void insertUser(String userId, String role, String area) {
        jdbc.update("""
                INSERT INTO users (user_id, name, password, role, language_code,
                                   ship_yard_area, remaining_leave_days, tts_enabled, work_shift, created_at)
                VALUES (?, ?, ?, ?, 'ko', ?, 15, false, 'MORNING', ?)
                """, userId, userId.substring(0, Math.min(10, userId.length())),
                "$2a$10$" + "0".repeat(53), role, area, LocalDateTime.of(2026, 1, 1, 0, 0));
    }
}
