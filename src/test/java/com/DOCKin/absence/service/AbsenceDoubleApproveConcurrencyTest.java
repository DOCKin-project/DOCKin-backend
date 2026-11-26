package com.DOCKin.absence.service;

import com.DOCKin.absence.dto.AbsenceRequestCreateRequestDto;
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
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 휴가 신청 <b>한 건</b>을 관리자 둘이 동시에 승인한다.
 *
 * <p>{@code LeaveBalanceConcurrencyTest}·ADR-0001이 본 것은 <b>서로 다른 두 건</b>이다. 그쪽은 신청자 행 락이 막는다.
 * 같은 한 건이면 신청자 행 락으로는 안 막힌다 — 둘 다 "PENDING인가"를 락 없이 읽고 통과한 뒤
 * 신청자 행 락에서 차례로 줄을 서서 <b>각자 차감</b>한다. 승인 재검사는 자기 자신을 빼고,
 * V11 EXCLUDE도 같은 행끼리는 걸리지 않는다.
 *
 * <h3>겹침을 우연에 맡기지 않는다</h3>
 * 테스트가 먼저 신청자 행을 {@code FOR UPDATE}로 잡아 둔다. 그러면
 * <ul>
 *   <li>신청 행 락이 없을 때: 두 승인 모두 PENDING 검사를 통과하고 신청자 행 락에서 멈춘다.</li>
 *   <li>신청 행 락이 있을 때: 첫째는 신청 행을 잡고 신청자 행 락에서, 둘째는 신청 행 락에서 멈춘다.</li>
 * </ul>
 * 어느 쪽이든 <b>두 백엔드가 락 대기에 들어간 것을 {@code pg_stat_activity}로 확인한 뒤</b> 놓는다.
 * 스케줄링에 따라 한쪽이 먼저 끝나 버려 "막혔다"처럼 보이는 경우가 없다.
 */
@SpringBootTest
@DisplayName("휴가 - 같은 신청을 동시에 두 번 승인해도 연차는 한 번만 깎인다")
class AbsenceDoubleApproveConcurrencyTest extends ContainerTestSupport {

    private static final String ADMIN_A = "dbl-admin-a";
    private static final String ADMIN_B = "dbl-admin-b";
    private static final String WORKER = "dbl-worker";
    /** 2027-04-05(월)~04-09(금): 근무일 5일. */
    private static final LocalDate MON = LocalDate.of(2027, 4, 5);
    private static final LocalDate FRI = LocalDate.of(2027, 4, 9);
    private static final int INITIAL = 15;

    @Autowired
    private AbsenceRequestService service;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void users() {
        for (String[] u : new String[][]{{ADMIN_A, "ADMIN"}, {ADMIN_B, "ADMIN"}, {WORKER, "USER"}}) {
            jdbc.sql("""
                    INSERT INTO users (user_id, created_at, language_code, name, password,
                                       remaining_leave_days, role, ship_yard_area, tts_enabled)
                    VALUES (:u, now(), 'ko', 'dbl', 'x', :d, :r, 'A', false)
                    ON CONFLICT (user_id) DO NOTHING
                    """).param("u", u[0]).param("d", INITIAL).param("r", u[1]).update();
        }
    }

    @AfterEach
    void clean() {
        List<String> ids = List.of(ADMIN_A, ADMIN_B, WORKER);
        jdbc.sql("DELETE FROM attendance WHERE user_id IN (:ids)").param("ids", ids).update();
        jdbc.sql("DELETE FROM absence_requests WHERE user_id IN (:ids)").param("ids", ids).update();
        jdbc.sql("DELETE FROM users WHERE user_id IN (:ids)").param("ids", ids).update();
    }

    @Test
    @DisplayName("관리자 A·B가 같은 신청을 동시에 승인 - 하나만 성공, 다른 하나는 ALREADY_PROCESSED, 잔액 15→10")
    void 같은_신청_동시_승인() throws Exception {
        Integer requestId = service.createRequest(WORKER, AbsenceRequestCreateRequestDto.builder()
                .type(AbsenceType.VACATION).startDate(MON).endDate(FRI).reason("연차").build(), null)
                .getRequestId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Object>> results = new ArrayList<>();
        try (Connection gate = dataSource.getConnection()) {
            gate.setAutoCommit(false);
            try (PreparedStatement ps = gate.prepareStatement(
                    "SELECT 1 FROM users WHERE user_id = ? FOR UPDATE")) {
                ps.setString(1, WORKER);
                ps.executeQuery().close();
            }

            for (String admin : List.of(ADMIN_A, ADMIN_B)) {
                results.add(pool.submit(() -> {
                    try {
                        return service.approveRequest(admin, requestId, "ok");
                    } catch (BusinessException e) {
                        return e.getErrorCode();
                    }
                }));
            }

            awaitLockWaiters(2);
            gate.rollback();
        }

        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : results) {
            outcomes.add(f.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes).as("한 건은 승인되고 한 건은 이미 처리됨으로 끝난다")
                .filteredOn(o -> o instanceof ErrorCode)
                .containsExactly(ErrorCode.ABSENCE_REQUEST_ALREADY_PROCESSED);
        assertThat(jdbc.sql("SELECT remaining_leave_days FROM users WHERE user_id = :u")
                .param("u", WORKER).query(Integer.class).single())
                .as("근무일 5일이 한 번만 깎인다 (락이 없으면 5가 된다)")
                .isEqualTo(INITIAL - 5);
        assertThat(jdbc.sql("SELECT count(*) FROM attendance WHERE user_id = :u AND status = 'VACATION'")
                .param("u", WORKER).query(Integer.class).single()).isEqualTo(5);
    }

    /** 두 승인 트랜잭션이 모두 행 락을 기다리는 상태가 될 때까지 기다린다. 10초 안에 안 되면 실패. */
    private void awaitLockWaiters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = jdbc.sql("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """).query(Integer.class).single();
            if (waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("락 대기에 들어간 트랜잭션이 " + waiting + "개뿐이다 - 겹침이 성립하지 않았다");
    }
}
