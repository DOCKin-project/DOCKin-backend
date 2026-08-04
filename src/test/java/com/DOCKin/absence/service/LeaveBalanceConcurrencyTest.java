package com.DOCKin.absence.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * <p>실제 서비스 코드가 아니라 동일한 읽기-검사-쓰기 순서를 JDBC로 재현한다.
 * 스프링 컨텍스트 없이 <b>DB 락 동작 자체</b>를 격리해서 보기 위함이다.
 *
 * <p>MySQL이 없으면 실패가 아니라 skip 된다.
 * 실행: {@code DB_PASSWORD=... ./gradlew test --tests "*LeaveBalanceConcurrencyTest"}
 */
class LeaveBalanceConcurrencyTest {

    private static final String URL =
            "jdbc:postgresql://localhost:5432/dockindb";
    private static final String TABLE = "bench_leave_balance";
    private static final String USER_ID = "10001";

    /** 초기 잔여 연차 5일에 3일짜리 신청 두 건 → 하나만 승인 가능해야 한다. */
    private static final int INITIAL_DAYS = 5;
    private static final int REQUEST_DAYS = 3;

    @Test
    @DisplayName("락이 없으면 동시 승인 시 잔액을 초과해 차감된다 (lost update)")
    void 락_없으면_깨진다() throws Exception {
        String password = requirePassword();

        try (Connection setup = DriverManager.getConnection(URL, "root", password)) {
            prepare(setup);
            Result result = runConcurrentApprovals(password, false);

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
            Assumptions.abort("PostgreSQL(localhost:5432) 접속 실패로 검증을 건너뜁니다: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("SELECT ... FOR UPDATE를 걸면 한 건만 승인된다")
    void 비관적_락이_막는다() throws Exception {
        String password = requirePassword();

        try (Connection setup = DriverManager.getConnection(URL, "root", password)) {
            prepare(setup);
            Result result = runConcurrentApprovals(password, true);

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
            Assumptions.abort("PostgreSQL(localhost:5432) 접속 실패로 검증을 건너뜁니다: " + e.getMessage());
        }
    }

    /**
     * 두 스레드가 동시에 "읽기 → 검사 → 쓰기"를 수행한다.
     *
     * <p>{@code CountDownLatch}로 출발을 맞춰 겹치는 구간을 최대화한다.
     * 그렇지 않으면 우연히 순차 실행되어 락이 없어도 문제가 재현되지 않을 수 있다.
     */
    private Result runConcurrentApprovals(String password, boolean useLock) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger approved = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try (Connection conn = DriverManager.getConnection(URL, "root", password)) {
                    conn.setAutoCommit(false);
                    start.await();

                    // approveRequest()와 같은 순서: 읽고 → 검사하고 → 쓴다.
                    Integer remaining = readBalance(conn, useLock);
                    if (remaining != null && remaining >= REQUEST_DAYS) {
                        writeBalance(conn, remaining - REQUEST_DAYS);
                        conn.commit();
                        approved.incrementAndGet();
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

        try (Connection conn = DriverManager.getConnection(URL, "root", password)) {
            return new Result(approved.get(), readBalance(conn, false));
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

    private String requirePassword() {
        String password = System.getenv("DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "DB_PASSWORD 환경변수가 없어 검증을 건너뜁니다.");
        return password;
    }

    private record Result(int approved, Integer finalBalance) {}
}
