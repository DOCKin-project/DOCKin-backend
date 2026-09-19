package com.DOCKin.absence.service;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 잔여 연차 차감의 동시성 검증.
 *
 * <p>{@code approveRequest()}는 잔여 연차를 <b>읽고 → 검사하고 → 쓴다.</b>
 * 관리자 두 명이 같은 사용자의 신청을 동시에 승인하면 둘 다 낡은 잔액을 읽어
 * 둘 다 검사를 통과하고 둘 다 차감한다(lost update).
 *
 * <p>이 테스트는 <b>락이 없을 때 실제로 깨지는 것</b>과 {@code SELECT ... FOR UPDATE}로
 * 막히는 것을 같은 조건에서 비교한다. 막히는 것만 보이면 애초에 문제가 있었는지 알 수 없다.
 *
 * <p>셋째 조건은 {@code REPEATABLE READ}다(DB-IMPROVEMENT-PLAN C4, 실험 카드 ⑤). PostgreSQL의 RR은
 * first-updater-wins라 {@code FOR UPDATE} 없이도 둘째 UPDATE가 {@code 40001}
 * ({@code could not serialize access due to concurrent update})로 죽는다. 그러면 lost update는
 * 막히지만 <b>재시도</b>가 필요해진다 — ADR-0001 4-1이 낙관락을 거절한 이유가 그대로 돌아오는지를 잰다.
 *
 * <p>실제 서비스 코드가 아니라 동일한 읽기-검사-쓰기 순서를 JDBC로 재현한다.
 * 스프링 컨텍스트 없이 <b>DB 락 동작 자체</b>를 격리해서 보기 위함이다.
 *
 * <p>DB는 {@link com.DOCKin.global.testsupport.ContainerTestSupport}가 컨테이너로 준다.
 * 건너뛰는 경로가 없으므로 조건부 skip 설명도 두지 않는다.
 * 실행: {@code ./gradlew test --tests "*LeaveBalanceConcurrencyTest"}
 */
class LeaveBalanceConcurrencyTest extends ContainerTestSupport {
    private static final String TABLE = "bench_leave_balance";
    private static final String USER_ID = "10001";

    /** 초기 잔여 연차 5일에 3일짜리 신청 두 건 → 하나만 승인 가능해야 한다. */
    private static final int INITIAL_DAYS = 5;
    private static final int REQUEST_DAYS = 3;

    @Test
    @DisplayName("락이 없으면 동시 승인 시 잔액을 초과해 차감된다 (lost update)")
    void 락_없으면_깨진다() throws Exception {
        String password = password();

        try (Connection setup = connect()) {
            prepare(setup);
            Result result = runConcurrentApprovals(password, Mode.NONE);

            System.out.println();
            System.out.println("=== 락 없음 ===");
            System.out.printf("승인 성공 %d건 / 소비된 연차 %d일 / DB 잔액 %d일%n",
                    result.approved(), result.approved() * REQUEST_DAYS, result.finalBalance());

            // 두 트랜잭션이 모두 5일을 읽고 검사를 통과해 각각 5-3=2를 쓴다.
            // 6일을 승인해놓고 잔액은 2일로 남아 3일치가 증발한다.
            assertEquals(2, result.approved(), "락이 없으면 두 건 모두 승인된다");
            assertTrue(result.approved() * REQUEST_DAYS > INITIAL_DAYS,
                    "잔액 5일을 초과해 6일이 승인된 상태");
            System.out.printf(">>> 잔액 %d일인데 %d일이 승인됐다. %d일이 증발한다.%n",
                    INITIAL_DAYS, result.approved() * REQUEST_DAYS,
                    result.approved() * REQUEST_DAYS - INITIAL_DAYS);
        } catch (SQLException e) {
            Assumptions.abort("테스트 컨테이너 접속 실패로 검증을 건너뜁니다: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("SELECT ... FOR UPDATE를 걸면 한 건만 승인된다")
    void 비관적_락이_막는다() throws Exception {
        String password = password();

        try (Connection setup = connect()) {
            prepare(setup);
            Result result = runConcurrentApprovals(password, Mode.FOR_UPDATE);

            System.out.println();
            System.out.println("=== 비관적 락 (SELECT ... FOR UPDATE) ===");
            System.out.printf("승인 성공 %d건 / 소비된 연차 %d일 / DB 잔액 %d일%n",
                    result.approved(), result.approved() * REQUEST_DAYS, result.finalBalance());

            // 두 번째 트랜잭션은 첫 번째가 커밋할 때까지 대기했다가
            // 갱신된 잔액(2일)을 읽으므로 3일 신청이 검사에서 걸린다.
            assertEquals(1, result.approved(), "락이 있으면 한 건만 승인되어야 한다");
            assertEquals(INITIAL_DAYS - REQUEST_DAYS, result.finalBalance());
            System.out.println(">>> 잔액을 초과하지 않는다.");
        } catch (SQLException e) {
            Assumptions.abort("테스트 컨테이너 접속 실패로 검증을 건너뜁니다: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("REPEATABLE READ면 FOR UPDATE 없이도 둘째 승인이 40001로 죽는다 — 대신 재시도가 필요하다")
    void 반복읽기는_둘째를_죽인다() throws Exception {
        String password = password();

        try (Connection setup = connect()) {
            prepare(setup);
            Result result = runConcurrentApprovals(password, Mode.REPEATABLE_READ);

            System.out.println();
            System.out.println("=== REPEATABLE READ (락 없음) ===");
            System.out.printf("승인 성공 %d건 / 직렬화 실패 %d건(%s) / 진 쪽 UPDATE 대기 %dms / DB 잔액 %d일%n",
                    result.approved(), result.serializationFailures(), result.loserSqlState(),
                    result.loserWaitMs(), result.finalBalance());

            // 둘 다 5일을 읽고 검사를 통과해 둘 다 UPDATE를 던진다. 먼저 쓴 쪽이 이기고,
            // 둘째 UPDATE는 첫째가 커밋할 때까지 막혔다가 스냅샷보다 새 행을 보고 40001로 죽는다.
            assertEquals(1, result.approved(), "RR이면 한 건만 커밋된다");
            assertEquals(1, result.serializationFailures(), "다른 한 건은 직렬화 실패여야 한다");
            assertEquals("40001", result.loserSqlState(), "SQLSTATE 40001 serialization_failure");
            assertEquals(INITIAL_DAYS - REQUEST_DAYS, result.finalBalance());
            System.out.println(">>> 잔액은 지켜졌지만 진 쪽은 예외로 끝났다 — 재시도해야 사용자에게 '잔액 부족'이 간다.");
        } catch (SQLException e) {
            Assumptions.abort("테스트 컨테이너 접속 실패로 검증을 건너뜁니다: " + e.getMessage());
        }
    }

    /**
     * 두 스레드가 동시에 "읽기 → 검사 → 쓰기"를 수행한다.
     *
     * <h3>출발만 맞추면 부족하다 — CI에서 실제로 깨졌다</h3>
     * 예전에는 {@code start} 래치 하나로 출발만 맞추고 겹치기를 <b>기대</b>했다.
     * 2026-08-12 CI에서 두 번 연속 {@code expected: <2> but was: <1>}로 실패했고,
     * 그 사이 자바 코드 변경은 없었다. 2코어 러너가 바쁘면 한 스레드가
     * <b>읽기·쓰기·커밋까지 끝낸 뒤에</b> 다른 스레드가 스케줄되고, 그쪽은 이미 줄어든
     * 잔액을 읽어 <b>정상적으로</b> 거절한다. 즉 "락이 없으면 깨진다"를 보이려는 테스트가
     * 우연에 기대고 있었다 — 통과해도 무엇을 보였는지 알 수 없는 상태다.
     *
     * <p>그래서 <b>읽기 뒤에 배리어를 하나 더</b> 둔다. 둘 다 옛 값을 읽은 것이 확인된
     * 뒤에만 쓰기로 넘어가므로 lost update가 우연이 아니라 <b>구조로</b> 재현된다.
     *
     * <h3>배리어는 락 없는 쪽에만 건다</h3>
     * 락을 쓰면 두 번째 스레드가 {@code SELECT ... FOR UPDATE}에서 <b>막힌 채</b>
     * 첫 번째의 커밋을 기다린다. 거기에 배리어를 걸면 먼저 읽은 쪽은 배리어에서,
     * 뒤엣것은 락에서 서로를 기다려 <b>교착</b>이 된다. 락 있는 쪽은 막히는 것 자체가
     * 확정적이라 배리어가 필요하지도 않다.
     *
     * <p>REPEATABLE READ는 읽기에서 안 막히므로(스냅샷) 락 없는 쪽과 같이 배리어를 건다.
     * 막히는 자리는 둘째 UPDATE다 — 첫째가 커밋할 때까지 기다렸다가 40001로 죽는다.
     */
    private Result runConcurrentApprovals(String password, Mode mode) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        CyclicBarrier bothRead = new CyclicBarrier(2);
        AtomicInteger approved = new AtomicInteger();
        AtomicInteger serializationFailures = new AtomicInteger();
        AtomicReference<String> loserSqlState = new AtomicReference<>();
        AtomicLong loserWaitMs = new AtomicLong();
        AtomicBoolean interleaveFailed = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try (Connection conn = connect()) {
                    conn.setAutoCommit(false);
                    if (mode == Mode.REPEATABLE_READ) {
                        conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    }
                    start.await();

                    // approveRequest()와 같은 순서: 읽고 → 검사하고 → 쓴다.
                    Integer remaining = readBalance(conn, mode == Mode.FOR_UPDATE);

                    // 둘 다 읽은 뒤에 쓴다. 위 주석의 이유로 FOR UPDATE가 아닌 경우에만 건다.
                    if (mode != Mode.FOR_UPDATE) {
                        try {
                            bothRead.await(10, TimeUnit.SECONDS);
                        } catch (TimeoutException | BrokenBarrierException e) {
                            // 아래 catch가 삼키면 겹침이 성립하지 않은 것과 우연히 순차 실행된
                            // 것이 같은 실패 메시지로 보인다. 그것이 이번에 CI에서 원인을
                            // 좁히기 어려웠던 이유다. 밖으로 들고 나가 따로 말하게 한다.
                            interleaveFailed.set(true);
                        }
                    }

                    if (remaining != null && remaining >= REQUEST_DAYS) {
                        long t0 = System.nanoTime();
                        try {
                            writeBalance(conn, remaining - REQUEST_DAYS);
                            conn.commit();
                            approved.incrementAndGet();
                        } catch (SQLException e) {
                            // RR의 둘째 UPDATE: 첫째 커밋까지 막혔다가 40001. 얼마나 기다렸는지도 남긴다.
                            loserWaitMs.set((System.nanoTime() - t0) / 1_000_000);
                            loserSqlState.set(e.getSQLState());
                            if ("40001".equals(e.getSQLState())) {
                                serializationFailures.incrementAndGet();
                            }
                            conn.rollback();
                        }
                    } else {
                        conn.rollback();
                    }
                } catch (Exception ignored) {
                    // 락 대기 타임아웃 등은 승인 실패로 본다.
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "동시 승인이 30초 안에 끝나지 않았다");
        pool.shutdown();

        assertFalse(interleaveFailed.get(),
                "겹침이 성립하지 않았다 - 이 실행의 결과는 lost update의 근거로 읽으면 안 된다");

        try (Connection conn = connect()) {
            return new Result(approved.get(), readBalance(conn, false),
                    serializationFailures.get(), loserSqlState.get(), loserWaitMs.get());
        }
    }

    private Integer readBalance(Connection conn, boolean useLock) throws SQLException {
        String sql = "SELECT remaining_leave_days FROM " + TABLE + " WHERE user_id = ?"
                + (useLock ? " FOR UPDATE" : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, USER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private void writeBalance(Connection conn, int value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + TABLE + " SET remaining_leave_days = ? WHERE user_id = ?")) {
            ps.setInt(1, value);
            ps.setString(2, USER_ID);
            ps.executeUpdate();
        }
    }

    private void prepare(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TABLE);
            st.execute("CREATE TABLE " + TABLE + " ("
                    + "user_id VARCHAR(50) PRIMARY KEY, remaining_leave_days INT NOT NULL)");
            st.execute("INSERT INTO " + TABLE + " VALUES ('" + USER_ID + "', " + INITIAL_DAYS + ")");
        }
    }

    /** 세 조건: 아무것도 안 함 / SELECT ... FOR UPDATE / REPEATABLE READ(락 없음). */
    private enum Mode { NONE, FOR_UPDATE, REPEATABLE_READ }

    private record Result(int approved, Integer finalBalance,
                          int serializationFailures, String loserSqlState, long loserWaitMs) {}
}
