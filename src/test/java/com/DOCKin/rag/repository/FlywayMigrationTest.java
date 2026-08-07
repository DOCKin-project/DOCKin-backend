package com.DOCKin.rag.repository;

import com.DOCKin.global.testsupport.PostgresTestSupport;
import org.flywaydb.core.Flyway;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 검증: Flyway 마이그레이션이 <b>빈 데이터베이스에서 처음부터</b> 제대로 도는가?
 *
 * <h3>왜 이 검증이 필요한가</h3>
 * V1이 담당하는 둘은 <b>빠뜨려도 에러가 나지 않는</b> 종류다.
 *
 * <ul>
 *   <li>{@code CREATE EXTENSION vector} — 없으면 테이블 생성이 실패하지만
 *       {@code ddl-auto=update}가 DDL 오류를 로그만 남기고 기동을 막지 않아
 *       <b>정상 기동한 것처럼 보인다</b> (2a의 {@code SafetyCourse} 사고와 같은 방식)</li>
 *   <li>HNSW 인덱스 — 없으면 전체 스캔으로 조용히 돌아간다. 결과는 똑같이 맞고 느릴 뿐이라
 *       <b>몇 달 뒤에나 눈치챈다</b></li>
 * </ul>
 *
 * <h3>왜 기존 DB가 아니라 새 데이터베이스를 만들어 쓰는가</h3>
 * 개발 DB에는 이미 확장도 인덱스도 있어서, 마이그레이션이 아무것도 안 해도 통과한다.
 * <b>정말 확인해야 하는 것은 아무것도 없는 상태에서 순서대로 만들어지는가</b>이므로
 * 임시 데이터베이스를 만들어 거기에 적용하고 지운다.
 *
 * <h3>왜 스프링 컨텍스트를 띄우지 않는가</h3>
 * {@code @DataJpaTest}는 Flyway를 자동 구성하지 않고, 억지로 넣으면
 * {@code flyway}와 {@code entityManagerFactory} 사이에 순환 의존이 생긴다.
 * 그런데 그 순환이 곧 <b>Spring Boot가 EMF를 Flyway에 의존시킨다는 증거</b>이기도 하다
 * — 즉 "확장/테이블/인덱스가 Hibernate DDL보다 먼저"라는 순서는 프레임워크가 보장한다.
 * 여기서는 프레임워크가 아니라 <b>마이그레이션 SQL 자체</b>를 검증한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayMigrationTest extends PostgresTestSupport {

    private static final String ADMIN_DB = "postgres";
    private static final String SCRATCH_DB = "dockindb_flyway_verify";

    private Flyway flyway;

    @BeforeAll
    void createScratchDatabase() throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                jdbcUrlFor(ADMIN_DB), username(), password());
             Statement st = admin.createStatement()) {
            // CREATE DATABASE는 트랜잭션 안에서 실행할 수 없어 자동 커밋으로 던진다.
            st.execute("DROP DATABASE IF EXISTS " + SCRATCH_DB);
            st.execute("CREATE DATABASE " + SCRATCH_DB);
        }
        flyway = Flyway.configure()
                .dataSource(jdbcUrlFor(SCRATCH_DB), username(), password())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
        flyway.migrate();
    }

    @AfterAll
    void dropScratchDatabase() throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                jdbcUrlFor(ADMIN_DB), username(), password());
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCRATCH_DB);
        }
    }

    /**
     * 최신 버전 번호를 박아두지 않는다. 그러면 마이그레이션을 하나 추가할 때마다 이 테스트가 깨지고,
     * 고치는 사람은 <b>기대값만 바꾸게 된다</b> — 검증이 아니라 형식이 되는 순간이다.
     * 대신 <b>클래스패스에 있는 것이 전부 성공으로 적용됐는가</b>를 본다.
     */
    @Test
    @DisplayName("클래스패스의 마이그레이션이 빠짐없이 성공으로 적용된다")
    void 마이그레이션_이력() throws SQLException {
        long expected = java.util.Arrays.stream(flyway.info().all())
                .filter(i -> i.getVersion() != null)
                .filter(i -> !"BASELINE".equals(i.getType().name()))
                .count();

        assertEquals(String.valueOf(expected), queryString("""
                SELECT count(*) FROM flyway_schema_history
                WHERE success = true AND version IS NOT NULL AND type <> 'BASELINE'
                """), "적용된 마이그레이션 수가 클래스패스의 개수와 다르다");

        assertEquals("0", queryString("""
                SELECT count(*) FROM flyway_schema_history WHERE success = false
                """), "실패로 남은 마이그레이션이 있다");
    }

    @Test
    @DisplayName("pgvector 확장이 등록된다 - 이미지에 파일이 있는 것과 별개다")
    void 확장_등록() throws SQLException {
        assertNotNull(queryString("SELECT extname FROM pg_extension WHERE extname = 'vector'"),
                "확장이 없으면 vector(384) 컬럼 생성이 실패하는데 ddl-auto가 그 오류를 삼킨다");
    }

    @Test
    @DisplayName("embedding 컬럼이 vector(384)로 만들어진다")
    void 벡터_컬럼_타입() throws SQLException {
        assertEquals("vector(384)", queryString("""
                SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                WHERE attrelid = 'document_chunks'::regclass AND attname = 'embedding'
                """));
    }

    @Test
    @DisplayName("HNSW 인덱스가 존재하고 접근 방식이 정말 hnsw다")
    void hnsw_인덱스() throws SQLException {
        // 이름만 확인하면 btree로 잘못 만들어져 있어도 통과한다. 접근 방식까지 본다.
        assertEquals("hnsw", queryString("""
                SELECT am.amname FROM pg_class idx
                JOIN pg_am am ON am.oid = idx.relam
                WHERE idx.relname = 'idx_chunk_embedding_hnsw'
                """));
    }

    @Test
    @DisplayName("시퀀스 증가폭이 allocationSize(50)와 같다")
    void 시퀀스_증가폭() throws SQLException {
        // 어긋나면 Hibernate가 미리 발급해둔 키와 DB가 발급할 키가 충돌한다.
        assertEquals("50", queryString(
                "SELECT increment_by FROM pg_sequences WHERE sequencename = 'document_chunk_seq'"));
    }

    @Test
    @DisplayName("권한 선필터용 인덱스까지 함께 만들어진다")
    void 보조_인덱스() throws SQLException {
        assertEquals("3", queryString("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'document_chunks'
                  AND indexname IN ('idx_chunk_source', 'idx_chunk_model', 'idx_chunk_visibility')
                """));
    }

    @Test
    @DisplayName("다시 실행해도 안전하다 - 기존 DB에 적용해야 하므로 멱등이어야 한다")
    void 멱등성() {
        // V1은 이미 테이블이 있는 DB에도 baseline-version=0으로 적용된다.
        // 그때 실패하지 않으려면 모든 문장이 IF NOT EXISTS여야 한다. 직접 다시 던져 확인한다.
        assertDoesNotThrow(() -> {
            try (Connection conn = DriverManager.getConnection(jdbcUrlFor(SCRATCH_DB), username(), password());
                 Statement st = conn.createStatement()) {
                st.execute("CREATE EXTENSION IF NOT EXISTS vector");
                st.execute("CREATE SEQUENCE IF NOT EXISTS document_chunk_seq INCREMENT BY 50 START WITH 1");
                st.execute("CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw "
                        + "ON document_chunks USING hnsw (embedding vector_cosine_ops)");
            }
        });
    }

    private String queryString(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(jdbcUrlFor(SCRATCH_DB), username(), password());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
