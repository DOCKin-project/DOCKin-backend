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
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검증: 비관적 락의 대기 한도가 실제로 적용되는가?
 *
 * <h3>먼저 시도했다가 버린 방법 — JPA 힌트</h3>
 * {@code jakarta.persistence.lock.timeout} 힌트를 3초로 걸어봤으나 <b>MySQL에서 조용히 무시됐다.</b>
 * 실측 <b>50,850ms</b>로, {@code innodb_lock_wait_timeout} 기본값 50초가 그대로 적용됐고
 * 생성된 SQL에도 대기 시간이 없었다({@code ... for update of m1_0}).
 * MySQL은 {@code NOWAIT}과 {@code SKIP LOCKED}만 지원하고 임의의 대기 시간 문법이 없기 때문이다
 * (Oracle의 {@code FOR UPDATE WAIT n}에 해당하는 것이 없다).
 *
 * <p>앞서 {@code hibernate.jdbc.batch_size}가 IDENTITY 전략 때문에 무시되던 것과 같은 종류의
 * 함정이다. <b>설정을 걸었다는 사실과 그것이 동작한다는 사실은 다르다.</b>
 *
 * <h3>채택한 방법 — 서버 파라미터</h3>
 * {@code compose.yaml}의 {@code --innodb-lock-wait-timeout=5}.
 * 승인 버튼을 누르고 50초를 기다린 끝에 실패하는 것보다 5초 만에 실패하는 편이 낫다.
 *
 * <h3>측정 방법</h3>
 * 별도 커넥션으로 대상 행에 {@code FOR UPDATE} 락을 잡아둔 채,
 * 리포지토리가 같은 행을 잠그려 할 때까지 걸리는 시간을 잰다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3308/dockindb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "spring.datasource.username=root",
        "spring.datasource.password=${DB_PASSWORD:}",
        "spring.jpa.hibernate.ddl-auto=update"
})
class LockTimeoutVerificationTest {

    private static final String URL =
            "jdbc:mysql://localhost:3308/dockindb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String USER_ID = "lock-test-user";

    /** compose.yaml의 --innodb-lock-wait-timeout=5 에 대응하는 기대값(ms). */
    private static final long EXPECTED_TIMEOUT_MS = 5000;
    /** MySQL 기본값. 이 근처가 나오면 설정이 적용되지 않은 것이다. */
    private static final long DEFAULT_TIMEOUT_MS = 50_000;

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
    @DisplayName("락 대기 한도가 설정값(5초)대로 적용되는지")
    void 락_타임아웃_검증() throws Exception {
        String password = System.getenv("DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "DB_PASSWORD 환경변수가 없어 검증을 건너뜁니다.");

        seedUser(password);

        // 다른 커넥션이 대상 행을 잠그고 커밋하지 않은 채 유지한다.
        lockHolder = DriverManager.getConnection(URL, "root", password);
        lockHolder.setAutoCommit(false);
        try (Statement st = lockHolder.createStatement()) {
            st.executeQuery("SELECT * FROM users WHERE user_id = '" + USER_ID + "' FOR UPDATE");
        }

        long start = System.nanoTime();
        String outcome;
        try {
            memberRepository.findByUserIdForUpdate(USER_ID);
            outcome = "락을 획득했다 (경합이 없었다는 뜻이므로 측정 실패)";
        } catch (Exception e) {
            outcome = e.getClass().getSimpleName();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println();
        System.out.println("=== 락 대기 한도 검증 (MySQL) ===");
        System.out.printf("설정값(서버)    : %,d ms%n", EXPECTED_TIMEOUT_MS);
        System.out.printf("MySQL 기본값    : %,d ms%n", DEFAULT_TIMEOUT_MS);
        System.out.printf("실제 대기 시간  : %,d ms%n", elapsedMs);
        System.out.printf("결과            : %s%n", outcome);
        System.out.println();
        System.out.println(elapsedMs < DEFAULT_TIMEOUT_MS / 2
                ? ">>> 설정이 적용됐다."
                : ">>> 설정이 적용되지 않았다 - MySQL 기본값 50초가 쓰이고 있다.");
        System.out.println();

        assertNotNull(outcome);
        // 경합 자체는 반드시 발생해야 측정이 의미가 있다.
        assertTrue(elapsedMs > 100, "락 경합이 발생하지 않았다 - 측정 조건이 성립하지 않는다");
        // 기본값 50초의 절반보다 빨라야 설정이 먹은 것이다. 컨테이너 기동 지연을 감안해 여유를 둔다.
        assertTrue(elapsedMs < DEFAULT_TIMEOUT_MS / 2,
                "락 대기가 " + elapsedMs + "ms 걸렸다 - innodb_lock_wait_timeout 설정이 적용되지 않았다");
    }

    private void seedUser(String password) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, "root", password);
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM users WHERE user_id = '" + USER_ID + "'");
            st.execute("INSERT INTO users "
                    + "(user_id, name, password, role, language_code, tts_enabled, "
                    + " ship_yard_area, remaining_leave_days, created_at) VALUES "
                    + "('" + USER_ID + "', '테스트', 'pw', 'USER', 'ko', 0, '1도크', 15, NOW(6))");
        }
    }
}
