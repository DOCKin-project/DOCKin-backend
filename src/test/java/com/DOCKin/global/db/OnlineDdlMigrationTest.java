package com.DOCKin.global.db;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 무중단 DDL 절차의 첫 단계 — {@code CREATE INDEX CONCURRENTLY}가 Flyway 마이그레이션으로 나갈 수 있는가.
 * ({@code docs/db/online-ddl.md}, DB-IMPROVEMENT-PLAN E3, PRODUCTION-READINESS D5)
 *
 * <h3>왜 이것부터인가</h3>
 * V3 주석이 "CONCURRENTLY는 트랜잭션 안에서 실행할 수 없다"를 알고도 <b>그냥 일반 CREATE INDEX로 갔다</b> —
 * 테이블이 작아서였다. 테이블이 커지면 그 선택이 쓰기를 막는다: 일반 CREATE INDEX는 SHARE 락으로
 * 빌드 내내 INSERT/UPDATE/DELETE를 세운다. 100만 행 벤치에서 인덱스 빌드는 초 단위였고(P2-15-5), 그 초 동안
 * 근태 체크가 전부 {@code lock_timeout=5s}에 걸린다.
 *
 * <h3>처음 가설은 틀렸다 — 트랜잭션이 아니라 Flyway 자신의 락이 문제였다</h3>
 * 첫 판은 "{@code <script>.sql.conf}에 {@code executeInTransaction=false}가 있어야 되고, 없으면
 * {@code cannot run inside a transaction block}으로 실패한다"를 검증하려 했다. 둘 다 아니었다:
 * <ul>
 *   <li>Flyway 11의 PostgreSQL 파서는 {@code CREATE INDEX CONCURRENTLY}를 알아보고
 *       ({@code PostgreSQLParser.CREATE_INDEX_CONCURRENTLY_REGEX}) 그 스크립트를 <b>알아서 트랜잭션 밖에서</b>
 *       돌린다. {@code .sql.conf}는 필요 없다.</li>
 *   <li>대신 두 판 모두 {@code 55P03 canceling statement due to lock timeout}으로 죽었다. Flyway는 히스토리 표를
 *       지키는 advisory lock을 기본으로 <b>트랜잭션 수준</b>으로 잡아, 마이그레이션이 도는 내내 락 커넥션이
 *       {@code idle in transaction}이다. CONCURRENTLY는 마지막 단계에서 "나보다 오래된 스냅샷을 가진
 *       트랜잭션"이 모두 끝나기를 기다리는데, 그 트랜잭션이 <b>Flyway 자신</b>이라 영원히 안 끝난다
 *       ({@code log_lock_waits}: "still waiting for ShareLock on virtual transaction"). 우리 서버는
 *       {@code lock_timeout=5s}라 5초 뒤 실패로 끝나지만, 기본값 0인 서버였다면 기동이 <b>멈춘다</b>.</li>
 * </ul>
 * 해법은 {@code flyway.postgresql.transactional.lock=false} — advisory lock을 세션 수준으로 잡아 락 커넥션에
 * 열린 트랜잭션이 없게 한다. 이 테스트는 그 설정이 <b>없으면 실제로 lock_timeout에 걸리고</b>,
 * <b>있으면 유효한 인덱스가 만들어지는지</b>를 둘 다 본다 — 한쪽만 보면 "원래 되는 것"과 구분이 안 된다.
 *
 * <p>스키마는 운영 것이 아니라 {@code src/test/resources/db/online-ddl-lab}의 표본이다. 운영
 * 마이그레이션에 이 절차를 처음 쓰는 것은 D1({@code pg_trgm}) 결정 뒤다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnlineDdlMigrationTest extends ContainerTestSupport {

    private static final String ADMIN_DB = "postgres";
    private static final String DB_SESSION_LOCK = "dockindb_online_ddl";
    private static final String DB_TX_LOCK = "dockindb_online_ddl_txlock";
    private static final String LAB = "classpath:db/online-ddl-lab";

    @BeforeAll
    void createScratchDatabases() throws SQLException {
        try (Connection admin = DriverManager.getConnection(jdbcUrlFor(ADMIN_DB), username(), password());
             Statement st = admin.createStatement()) {
            for (String db : new String[]{DB_SESSION_LOCK, DB_TX_LOCK}) {
                st.execute("DROP DATABASE IF EXISTS " + db);
                st.execute("CREATE DATABASE " + db);
            }
        }
    }

    @AfterAll
    void dropScratchDatabases() throws SQLException {
        try (Connection admin = DriverManager.getConnection(jdbcUrlFor(ADMIN_DB), username(), password());
             Statement st = admin.createStatement()) {
            for (String db : new String[]{DB_SESSION_LOCK, DB_TX_LOCK}) {
                st.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
            }
        }
    }

    @Test
    @DisplayName("flyway.postgresql.transactional.lock=false 이면 CONCURRENTLY 인덱스가 .sql.conf 없이 마이그레이션으로 만들어진다")
    void 세션_락이면_된다() throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrlFor(DB_SESSION_LOCK), username(), password())
                .locations(LAB)
                // application.properties의 spring.flyway.postgresql.transactional-lock=false 와 같은 것
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .load();
        int applied = flyway.migrate().migrationsExecuted;
        assertEquals(2, applied, "V1·V2 두 개가 적용돼야 한다");

        try (Connection c = DriverManager.getConnection(jdbcUrlFor(DB_SESSION_LOCK), username(), password());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT i.indisvalid, i.indisready
                     FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid
                     WHERE ic.relname = 'idx_lab_rows_v'""")) {
            assertTrue(rs.next(), "인덱스가 없다");
            // CONCURRENTLY가 중간에 죽으면 인덱스는 남되 invalid다 -- 그 상태를 성공으로 읽지 않게 둘 다 본다.
            assertTrue(rs.getBoolean("indisvalid"), "인덱스가 invalid다 - CONCURRENTLY 빌드가 완주하지 못한 흔적");
            assertTrue(rs.getBoolean("indisready"));
        }
    }

    @Test
    @DisplayName("기본값(트랜잭션 수준 advisory lock)이면 Flyway 자신의 트랜잭션을 기다리다 lock_timeout에 걸린다")
    void 트랜잭션_락이면_자기_자신을_기다린다() throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrlFor(DB_TX_LOCK), username(), password())
                .locations(LAB)
                .load();
        FlywayException e = assertThrows(FlywayException.class, flyway::migrate,
                "기본값으로도 통과한다면 transactional.lock=false 는 필요 없다는 뜻 - 절차 문서를 고쳐야 한다");
        String msg = String.valueOf(e.getMessage()) + " / " + (e.getCause() == null ? "" : e.getCause().getMessage());
        assertTrue(msg.contains("canceling statement due to lock timeout"),
                "실패했지만 이유가 다르다: " + msg);
        assertFalse(msg.contains("cannot run inside a transaction block"),
                "Flyway가 CONCURRENTLY를 트랜잭션 안에서 돌렸다 - 파서 자동 감지가 사라졌다면 .sql.conf가 다시 필요하다");

        // 죽은 CONCURRENTLY는 invalid 인덱스를 남긴다. 운영에서 이 상태를 만나면 DROP INDEX 뒤 재시도다(online-ddl.md).
        try (Connection c = DriverManager.getConnection(jdbcUrlFor(DB_TX_LOCK), username(), password());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT i.indisvalid
                     FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid
                     WHERE ic.relname = 'idx_lab_rows_v'""")) {
            assertTrue(rs.next(), "실패한 CONCURRENTLY는 invalid 인덱스를 남겨야 한다");
            assertFalse(rs.getBoolean("indisvalid"));
        }
        // 히스토리엔 아무것도 안 남는다 -- 트랜잭션 밖이라 실패 행이 남을 줄 알았는데(첫 판의 가정) Flyway는 PostgreSQL에선
        // 성공 뒤에만 행을 쓴다. 그래서 복구는 invalid 인덱스 DROP 하나고, repair는 필요 없다(online-ddl.md 1절).
        try (Connection c = DriverManager.getConnection(jdbcUrlFor(DB_TX_LOCK), username(), password());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT success FROM flyway_schema_history WHERE version = '2'")) {
            assertFalse(rs.next(), "실패한 마이그레이션이 히스토리에 남았다 - online-ddl.md 1절의 복구 절차에 repair를 다시 넣어야 한다");
        }
    }
}
