package com.DOCKin.absence.service;

import com.DOCKin.absence.dto.AbsenceRequestCreateRequestDto;
import com.DOCKin.absence.dto.AbsenceRequestResponseDto;
import com.DOCKin.absence.model.AbsenceType;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검증: 연차는 근무일만 깎이고, 겹치는 휴가는 신청·승인·DB 세 겹에서 막힌다 (#104).
 *
 * <p>단위 테스트는 목으로 규칙을 본다. 여기는 실물 — 승인 뒤 {@code users.remaining_leave_days}와
 * {@code attendance} 행 수가 근무일 수인지, 서비스를 우회해 넣은 PENDING도 승인에서 걸리는지,
 * 서비스를 아예 거치지 않고 APPROVED 둘을 넣으면 V11 EXCLUDE가 거부하는지를 컨테이너 DB에서 본다.
 *
 * <p>2027-03-01(월)~03-07(일). 캘린더 등록 없음 → 평일 규칙으로 근무일 5.
 */
@SpringBootTest
@DisplayName("휴가 #104 - 연차는 근무일만, 겹침은 세 겹")
class AbsenceWorkingDaysAndOverlapTest extends ContainerTestSupport {

    private static final String ADMIN = "ov-admin";
    private static final String WORKER = "ov-worker";
    private static final LocalDate MON = LocalDate.of(2027, 3, 1);
    private static final LocalDate SUN = LocalDate.of(2027, 3, 7);

    @Autowired
    private AbsenceRequestService service;
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void users() {
        for (String[] u : new String[][]{{ADMIN, "ADMIN"}, {WORKER, "USER"}}) {
            jdbc.sql("""
                    INSERT INTO users (user_id, created_at, language_code, name, password,
                                       remaining_leave_days, role, ship_yard_area, tts_enabled)
                    VALUES (:u, now(), 'ko', 'ov', 'x', 15, :r, 'A', false)
                    ON CONFLICT (user_id) DO NOTHING
                    """).param("u", u[0]).param("r", u[1]).update();
        }
    }

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM attendance WHERE user_id IN (:a, :w)").param("a", ADMIN).param("w", WORKER).update();
        jdbc.sql("DELETE FROM absence_requests WHERE user_id IN (:a, :w)").param("a", ADMIN).param("w", WORKER).update();
        jdbc.sql("DELETE FROM users WHERE user_id IN (:a, :w)").param("a", ADMIN).param("w", WORKER).update();
    }

    @Test
    @DisplayName("월~일 7일 승인 - 잔액 15→10, 근태 VACATION 행 5개. 그 뒤 겹치는 신청은 409, 우회해 넣은 PENDING도 승인에서 409")
    void workingDaysAndOverlap() {
        AbsenceRequestResponseDto created = service.createRequest(WORKER, vacation(MON, SUN), null);
        service.approveRequest(ADMIN, created.getRequestId(), "ok");

        assertThat(balance()).as("달력 7일이 아니라 근무일 5일").isEqualTo(10);
        assertThat(jdbc.sql("SELECT count(*) FROM attendance WHERE user_id = :u AND status = 'VACATION'")
                .param("u", WORKER).query(Integer.class).single()).as("토·일엔 VACATION 행이 없다").isEqualTo(5);

        // 신청 시 — 하루라도 겹치면 409. 이미 APPROVED가 있다.
        assertThatThrownBy(() -> service.createRequest(WORKER, vacation(SUN, SUN.plusDays(3)), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ABSENCE_PERIOD_OVERLAP);
        // 안 겹치면 된다.
        AbsenceRequestResponseDto next = service.createRequest(WORKER, vacation(SUN.plusDays(1), SUN.plusDays(2)), null);

        // 서비스를 우회해 겹치는 PENDING을 넣는다(옛 데이터·다른 경로). 승인 시 재검사가 잡는다 — 잔액 그대로.
        Integer sneaked = jdbc.sql("""
                INSERT INTO absence_requests (user_id, request_type, start_date, end_date, reason, status, requested_at)
                VALUES (:u, 'VACATION', :s, :e, 'sneak', 'PENDING', now()) RETURNING request_id
                """).param("u", WORKER).param("s", MON.plusDays(3)).param("e", MON.plusDays(4))
                .query(Integer.class).single();
        assertThatThrownBy(() -> service.approveRequest(ADMIN, sneaked, "ok"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ABSENCE_PERIOD_OVERLAP);
        assertThat(balance()).isEqualTo(10);

        // 안 겹치는 건 승인된다 — 3/8 월·3/9 화 = 2일.
        service.approveRequest(ADMIN, next.getRequestId(), "ok");
        assertThat(balance()).isEqualTo(8);
    }

    @Test
    @DisplayName("토~일만 낸 연차는 신청 400 - 병가는 된다")
    void weekendOnly() {
        assertThatThrownBy(() -> service.createRequest(WORKER, vacation(SUN.minusDays(1), SUN), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.ABSENCE_NO_WORKING_DAYS);

        AbsenceRequestCreateRequestDto sick = AbsenceRequestCreateRequestDto.builder()
                .type(AbsenceType.SICK).startDate(SUN.minusDays(1)).endDate(SUN).reason("입원").build();
        assertThat(service.createRequest(WORKER, sick, null).getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("V11 - 서비스를 거치지 않고 APPROVED 둘을 겹치게 넣으면 DB가 거부한다 (exclusion_violation). REJECTED는 자리를 안 차지한다")
    void excludeConstraint() {
        insert("APPROVED", MON, MON.plusDays(2));
        insert("REJECTED", MON, MON.plusDays(2));                  // 같은 기간 REJECTED — 된다
        insert("PENDING", MON, MON.plusDays(2));                   // PENDING도 제약 밖 — 서비스가 막는 몫
        insert("APPROVED", MON.plusDays(3), MON.plusDays(3));      // 인접(안 겹침) — 된다

        assertThatThrownBy(() -> insert("APPROVED", MON.plusDays(2), MON.plusDays(4)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("absence_requests_no_overlap");
    }

    private void insert(String status, LocalDate start, LocalDate end) {
        jdbc.sql("""
                INSERT INTO absence_requests (user_id, request_type, start_date, end_date, reason, status, requested_at)
                VALUES (:u, 'VACATION', :s, :e, 'raw', :st, now())
                """).param("u", WORKER).param("s", start).param("e", end).param("st", status).update();
    }

    private int balance() {
        return jdbc.sql("SELECT remaining_leave_days FROM users WHERE user_id = :u")
                .param("u", WORKER).query(Integer.class).single();
    }

    private static AbsenceRequestCreateRequestDto vacation(LocalDate start, LocalDate end) {
        return AbsenceRequestCreateRequestDto.builder()
                .type(AbsenceType.VACATION).startDate(start).endDate(end).reason("연차").build();
    }
}
