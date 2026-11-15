package com.DOCKin.member.service;

import com.DOCKin.attendance.service.AttendanceService;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.member.dto.LoginRequestDto;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 회원 탈퇴 = 비활성화 + 익명화 (#100, 사용자 결정).
 *
 * <p>예전 {@code delete}는 근태·일지가 하나라도 있으면 FK(NO ACTION) 위반 500이었다. 이제 행이 남고, 기록도 남고,
 * 로그인·인증·결근 대상·인원 집계에서만 빠져야 한다.
 */
@SpringBootTest
@DisplayName("회원 탈퇴 - 기록은 남고 로그인·인증·배치·집계에서 빠진다 (#100)")
class MemberWithdrawTest extends ContainerTestSupport {

    private static final String PREFIX = "wd-";
    private static final String AREA = "탈퇴구역";
    private static final String ADMIN = PREFIX + "admin";
    private static final String LEAVER = PREFIX + "leaver";
    private static final String STAY = PREFIX + "stay";
    private static final String PASSWORD = "pw-1234!";

    @Autowired private MemberService memberService;
    @Autowired private CustomUserDetailsService userDetailsService;
    @Autowired private AttendanceService attendanceService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private Long logId;

    @BeforeEach
    void data() {
        cleanup();
        String hash = passwordEncoder.encode(PASSWORD);
        for (String[] u : new String[][]{{ADMIN, "ADMIN"}, {LEAVER, "USER"}, {STAY, "USER"}}) {
            jdbc.update("""
                    INSERT INTO users (user_id, name, password, role, language_code,
                                       ship_yard_area, remaining_leave_days, tts_enabled, work_shift, created_at)
                    VALUES (?, ?, ?, ?, 'ko', ?, 15, false, 'MORNING', ?)
                    """, u[0], u[0].substring(3), hash, u[1], AREA, LocalDateTime.of(2026, 1, 1, 0, 0));
        }
        // 탈퇴를 500으로 만들던 자식 행들 — 근태와 작업일지.
        jdbc.update("""
                INSERT INTO attendance (id, user_id, work_date, clock_in_time, status, work_shift)
                VALUES (9200000001, ?, DATE '2026-11-02', TIMESTAMP '2026-11-02 07:50', 'NORMAL', 'MORNING')
                """, LEAVER);
        logId = jdbc.queryForObject("""
                INSERT INTO work_logs (title, log_text, created_at, updated_at, user_id)
                VALUES ('남을 일지', '본문', now(), now(), ?) RETURNING log_id
                """, Long.class, LEAVER);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM work_logs WHERE user_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM attendance WHERE user_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM refresh_token WHERE user_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM users WHERE user_id LIKE ?", PREFIX + "%");
    }

    @Test
    @DisplayName("근태·일지가 있어도 500 없이 탈퇴되고, 기록은 남고 이름·비밀번호는 지워진다")
    void withdrawKeepsRecords() {
        memberService.deleteAccount(LEAVER, LEAVER);

        Map<String, Object> row = jdbc.queryForMap("SELECT name, password, deleted_at FROM users WHERE user_id = ?", LEAVER);
        assertThat(row.get("name")).isEqualTo(Member.WITHDRAWN_NAME);
        assertThat((String) row.get("password")).doesNotStartWith("$2");
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance WHERE user_id = ?", Integer.class, LEAVER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_id FROM work_logs WHERE log_id = ?", String.class, logId)).isEqualTo(LEAVER);
    }

    @Test
    @DisplayName("탈퇴 뒤 로그인은 없는 사원번호와 같은 답, 인증 필터의 회원 조회도 실패, 두 번째 탈퇴는 404")
    void withdrawnCannotAuthenticate() {
        memberService.deleteAccount(LEAVER, LEAVER);

        assertThatThrownBy(() -> memberService.login(new LoginRequestDto(LEAVER, PASSWORD)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.LOGIN_INPUT_INVALID);
        assertThatThrownBy(() -> memberService.login(new LoginRequestDto("wd-nobody", PASSWORD)))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.LOGIN_INPUT_INVALID);
        // JwtAuthFilter가 요청마다 부르는 경로 — 여기서 실패하면 탈퇴 전에 받은 액세스 토큰도 쓸 수 없다.
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername(LEAVER))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThatThrownBy(() -> memberService.deleteAccount(LEAVER, LEAVER))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);

        // 남은 사람은 그대로 로그인된다.
        assertThat(memberService.login(new LoginRequestDto(STAY, PASSWORD))).isNotNull();
    }

    @Test
    @DisplayName("결근 배치 대상에서 탈퇴 회원이 빠진다 - 퇴사자가 매일 결근 행을 얻지 않는다")
    void withdrawnNotAbsentTarget() {
        memberService.deleteAccount(LEAVER, LEAVER);

        assertThat(memberRepository.findByRoleAndDeletedAtIsNull(UserRole.USER))
                .extracting(Member::getUserId)
                .contains(STAY)
                .doesNotContain(LEAVER, ADMIN);
    }

    @Test
    @DisplayName("인원 집계는 그날 재직자 기준 - 탈퇴한 날까지는 세고, 다음 날부터 빠진다")
    void dailyHeadcountByTenure() {
        jdbc.update("UPDATE users SET deleted_at = TIMESTAMP '2026-11-10 10:00' WHERE user_id = ?", LEAVER);

        long before = attendanceService.getDailySummary(ADMIN, LocalDate.of(2026, 11, 9), AREA, null).headcount();
        long sameDay = attendanceService.getDailySummary(ADMIN, LocalDate.of(2026, 11, 10), AREA, null).headcount();
        long after = attendanceService.getDailySummary(ADMIN, LocalDate.of(2026, 11, 11), AREA, null).headcount();

        assertThat(before).isEqualTo(3);
        assertThat(sameDay).as("그날 안에 탈퇴했으면 그날까지는 센다").isEqualTo(3);
        assertThat(after).isEqualTo(2);
    }
}
