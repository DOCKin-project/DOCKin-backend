package com.DOCKin.member.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검증: 비관적 락의 대기 한도가 실제로 적용되는가?
 *
 * <h3>MySQL 시절 — JPA 힌트는 무시됐다</h3>
 * {@code jakarta.persistence.lock.timeout}을 3초로 걸었으나 실측 <b>50,850ms</b>였다.
 * {@code innodb_lock_wait_timeout} 기본값 50초가 그대로 적용됐고 생성된 SQL에도 대기 시간이 없었다
 * ({@code ... for update of m1_0}). MySQL에 임의의 대기 시간 문법이 없기 때문이다.
 * 결국 서버 파라미터({@code --innodb-lock-wait-timeout=5})로 해결했고,
 * <b>서버 전역으로만 조정할 수 있어</b> "승인에는 적당하지만 배치에는 짧다"는 문제가 남았다.
 *
 * <h3>PostgreSQL 이관 후</h3>
 * {@code lock_timeout} 파라미터를 5초로 두되, <b>{@code SET LOCAL}로 트랜잭션마다 다른 값</b>을 줄 수 있다.
 * MySQL에서 남았던 트레이드오프가 사라지는 지점이라 함께 검증한다.
 *
 * <h3>측정 방법</h3>
 * 별도 커넥션으로 대상 행에 {@code FOR UPDATE} 락을 잡아둔 채,
 * 같은 행을 잠그려 할 때까지 걸리는 시간을 잰다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/dockindb",
        "spring.datasource.username=root",
        "spring.datasource.password=${DB_PASSWORD:}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=update"
})
class LockTimeoutVerificationTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/dockindb";
    private static final String USER_ID = "lock-test-user";

    /** compose.yaml의 -c lock_timeout=5s 에 대응하는 기대값(ms). */
    private static final long SERVER_TIMEOUT_MS = 5000;
    /** MySQL 시절 기본값. 비교용. */
    private static final long MYSQL_DEFAULT_MS = 50_000;
    /** SET LOCAL로 트랜잭션에만 적용해볼 값(ms). */
    private static final long LOCAL_TIMEOUT_MS = 1000;

    @Autowired
    private MemberRepository memberRepository;

    private Connection lockHolder;

    @AfterEach
    void cleanUp() throws SQLException {
        if (lockHolder != null && !lockHolder.isClosed()) {
            lockHolder.rollback();
            lockHolder.close();
        }
        String password = System.getenv("DB_PASSWORD");
        if (password != null && !password.isBlank()) {
            try (Connection conn = DriverManager.getConnection(URL, "root", password);
                 Statement st = conn.createStatement()) {
                st.execute("DELETE FROM users WHERE user_id = '" + USER_ID + "'");
            } catch (SQLException ignored) {
                // 정리 실패는 검증 결과에 영향을 주지 않는다.
            }
        }
    }

    @Test
    @DisplayName("락 대기 한도가 서버 설정(5초)대로 적용되고, SET LOCAL로 트랜잭션 단위 조정이 되는지")
    void 락_타임아웃_검증() throws Exception {
        String password = System.getenv("DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "DB_PASSWORD 환경변수가 없어 검증을 건너뜁니다.");

        seedUser(password);
        holdLock(password);

        // ① 리포지토리 경로 — 서버 기본값(5초)이 적용되어야 한다.
        long start = System.nanoTime();
        String outcome;
        try {
            memberRepository.findByUserIdForUpdate(USER_ID);
            outcome = "락 획득 (경합이 없었다는 뜻이므로 측정 실패)";
        } catch (Exception e) {
            outcome = e.getClass().getSimpleName();
        }
        long serverMs = (System.nanoTime() - start) / 1_000_000;

        // ② SET LOCAL — 같은 락에 대해 트랜잭션 단위로 더 짧은 한도를 준다.
        long localMs = measureWithLocalTimeout(password);

        System.out.println();
        System.out.println("=== 락 대기 한도 검증 (PostgreSQL) ===");
        System.out.printf("서버 설정(lock_timeout) : %,d ms%n", SERVER_TIMEOUT_MS);
        System.out.printf("  → 실제 대기           : %,d ms  (%s)%n", serverMs, outcome);
        System.out.printf("SET LOCAL 지정값        : %,d ms%n", LOCAL_TIMEOUT_MS);
        System.out.printf("  → 실제 대기           : %,d ms%n", localMs);
        System.out.printf("참고: MySQL 기본값      : %,d ms%n", MYSQL_DEFAULT_MS);
        System.out.println();
        System.out.println(localMs < serverMs
                ? ">>> SET LOCAL이 서버 기본값보다 우선한다. 트랜잭션마다 다른 한도를 줄 수 있다."
                : ">>> SET LOCAL이 반영되지 않았다.");
        System.out.println();

        assertNotNull(outcome);
        assertTrue(serverMs > 100, "락 경합이 발생하지 않았다 - 측정 조건이 성립하지 않는다");
        assertTrue(serverMs < MYSQL_DEFAULT_MS / 2,
                "락 대기가 " + serverMs + "ms 걸렸다 - lock_timeout 설정이 적용되지 않았다");
        assertTrue(localMs < serverMs,
                "SET LOCAL(" + localMs + "ms)이 서버 기본값(" + serverMs + "ms)보다 짧아야 한다");
    }

    /** {@code SET LOCAL lock_timeout}을 건 트랜잭션에서 같은 락을 시도하고 대기 시간을 잰다. */
    private long measureWithLocalTimeout(String password) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, "root", password)) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // SET LOCAL은 현재 트랜잭션에만 적용되고 커밋/롤백 시 사라진다.
                // MySQL의 innodb_lock_wait_timeout에는 이 범위가 없다.
                st.execute("SET LOCAL lock_timeout = '" + LOCAL_TIMEOUT_MS + "ms'");

                long start = System.nanoTime();
                try {
                    st.executeQuery("SELECT * FROM users WHERE user_id = '" + USER_ID + "' FOR UPDATE");
                } catch (SQLException expected) {
                    // 대기 한도 초과. 기대한 결과다.
                }
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                conn.rollback();
                return elapsed;
            }
        }
    }

    /** 다른 커넥션이 대상 행을 잠그고 커밋하지 않은 채 유지한다. */
    private void holdLock(String password) throws SQLException {
        lockHolder = DriverManager.getConnection(URL, "root", password);
        lockHolder.setAutoCommit(false);
        try (Statement st = lockHolder.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT * FROM users WHERE user_id = '" + USER_ID + "' FOR UPDATE")) {
            rs.next();
        }
    }

    private void seedUser(String password) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, "root", password);
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM users WHERE user_id = '" + USER_ID + "'");
            st.execute("INSERT INTO users "
                    + "(user_id, name, password, role, language_code, tts_enabled, "
                    + " ship_yard_area, remaining_leave_days, created_at) VALUES "
                    + "('" + USER_ID + "', '테스트', 'pw', 'USER', 'ko', false, '1도크', 15, now())");
        }
    }
}
