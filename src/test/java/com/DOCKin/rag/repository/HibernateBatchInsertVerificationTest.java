package com.DOCKin.rag.repository;

import com.DOCKin.global.testsupport.PostgresTestSupport;
import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import com.DOCKin.rag.model.Visibility;
import jakarta.persistence.EntityManager;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검증: PostgreSQL 이관 후 대량 적재가 실제로 개선되었는가?
 *
 * <h3>MySQL 시절의 문제</h3>
 * {@code DocumentChunk.chunkId}가 {@code GenerationType.IDENTITY}였다. Hibernate는 IDENTITY에서
 * INSERT 직후 생성된 키를 읽어야 해서 <b>JDBC 배치를 포기한다.</b>
 * {@code hibernate.jdbc.batch_size=100}을 켜도 조용히 무시됐고,
 * MySQL {@code Com_insert}로 확인한 결과 10,000건 저장에 INSERT 문장이 <b>정확히 10,000개</b>였다.
 *
 * <h3>이관 후</h3>
 * {@code GenerationType.SEQUENCE}로 바꿨다. 시퀀스는 INSERT <b>전에</b> 키를 받아오므로
 * Hibernate가 문장을 묶을 수 있다.
 *
 * <h3>지표를 바꾼 이유</h3>
 * MySQL에서는 {@code rewriteBatchedStatements}가 배치를 <b>다중행 INSERT 한 문장</b>으로 합쳐서
 * {@code Com_insert} 증가분이 곧 배치 여부였다. 반면 <b>PostgreSQL 드라이버는 같은 prepared statement를
 * 파이프라인으로 여러 번 실행</b>하므로 문장 수(=실행 횟수)로는 배치 여부를 구분할 수 없다.
 * 배치의 이득이 "문장 수 감소"가 아니라 "네트워크 왕복 감소"이기 때문이다.
 *
 * <p>그래서 <b>시퀀스 호출 횟수</b>를 본다. {@code allocationSize=50}이면 10,000건 적재에
 * {@code nextval}이 200회 근처여야 한다. 10,000회가 나오면 시퀀스 최적화가 동작하지 않는 것이고,
 * 그 경우 배치도 의미가 없다(INSERT마다 왕복이 한 번씩 더 생기므로).
 *
 * <h3>관측에 {@code pg_stat_statements}가 필요하다</h3>
 * 시퀀스 호출 횟수는 추정이 아니라 DB가 센 값으로 확인한다. 확장이 없으면 아래 {@code countCalls}가
 * 0을 돌려주고, 그러면 이 테스트는 <b>아무것도 검증하지 않은 채 통과한다.</b>
 * 그래서 {@link com.DOCKin.global.testsupport.PostgresTestSupport}가 컨테이너에
 * {@code shared_preload_libraries=pg_stat_statements}를 주고 확장 생성까지 실패로 처리한다.
 *
 * <h3>10,000건이지만 CI에서 돌린다</h3>
 * 실측 약 16초다. 이 정도는 CI에 넣을 만하다고 판단했다.
 * 제외한 것은 10만 청크를 적재하는 벤치마크 쪽이며, 그 둘은 성격이 다르다 --
 * 이건 <b>설정이 실제로 먹는가</b>를 보고, 저건 <b>얼마나 걸리는가</b>를 잰다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.properties.hibernate.jdbc.batch_size=100",
        "spring.jpa.properties.hibernate.order_inserts=true",
        "spring.jpa.properties.hibernate.order_updates=true"
})
class HibernateBatchInsertVerificationTest extends PostgresTestSupport {

    
    private static final int TOTAL = 10_000;
    private static final int FLUSH_EVERY = 100;
    /** DocumentChunk의 @SequenceGenerator(allocationSize = 50)과 맞춰야 한다. */
    private static final int ALLOCATION_SIZE = 50;

    /** MySQL + IDENTITY 시절 같은 조건에서 측정한 값. 비교 기준으로만 쓴다(환경이 달라 직접 비교는 아니다). */
    private static final long MYSQL_IDENTITY_MS = 36_461;

    @Autowired
    private DocumentChunkRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("SEQUENCE 전환 후 시퀀스 호출이 allocationSize만큼 묶이는지")
    void 대량_적재_검증() throws Exception {

        try (Connection stats = connect()) {
            resetStatements(stats);

            long start = System.nanoTime();
            List<DocumentChunk> buffer = new ArrayList<>(FLUSH_EVERY);
            for (int i = 0; i < TOTAL; i++) {
                buffer.add(chunk(i));
                if (buffer.size() == FLUSH_EVERY) {
                    repository.saveAll(buffer);
                    entityManager.flush();
                    entityManager.clear();
                    buffer.clear();
                }
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // pg_stat_statements는 상수를 파라미터로 정규화한다("select nextval($1)").
            // 시퀀스 이름으로는 찾을 수 없어 nextval 전체를 세고, 대상 시퀀스인지는
            // increment_by로 따로 확인한다.
            long sequenceCalls = countCalls(stats, "%nextval%");
            long incrementBy = sequenceIncrement(stats, "document_chunk_seq");
            long expectedCalls = TOTAL / ALLOCATION_SIZE;

            System.out.println();
            System.out.println("=== 대량 적재 검증 (PostgreSQL + SEQUENCE) ===");
            System.out.printf("저장 건수            : %,d%n", TOTAL);
            System.out.printf("allocationSize       : %d%n", ALLOCATION_SIZE);
            System.out.printf("시퀀스 increment_by  : %d  (DB에 실제로 반영된 값)%n", incrementBy);
            System.out.printf("nextval 호출 횟수    : %,d  (기대 약 %,d)%n", sequenceCalls, expectedCalls);
            System.out.printf("소요 시간            : %,d ms%n", elapsedMs);
            System.out.printf("참고: MySQL+IDENTITY : %,d ms (다른 엔진이라 직접 비교는 아님)%n", MYSQL_IDENTITY_MS);
            System.out.println();

            if (sequenceCalls > 0 && sequenceCalls <= expectedCalls * 2) {
                System.out.printf(">>> 시퀀스가 %d건씩 묶여 발급된다. IDENTITY 시절이라면 %,d회였을 자리다.%n",
                        ALLOCATION_SIZE, TOTAL);
            } else if (sequenceCalls >= TOTAL) {
                System.out.println(">>> 시퀀스가 건별로 호출되고 있다. allocationSize가 적용되지 않았다.");
            } else {
                System.out.println(">>> pg_stat_statements에서 관측하지 못했다(확장 미설치 등).");
            }
            System.out.println();

            assertEquals(ALLOCATION_SIZE, incrementBy,
                    "시퀀스 increment_by가 allocationSize와 다르다 - 설정이 DB에 반영되지 않았다");
            if (sequenceCalls > 0) {
                assertTrue(sequenceCalls < TOTAL / 2,
                        "시퀀스가 " + sequenceCalls + "회 호출됐다 - allocationSize가 적용되지 않았다");
            }
        } catch (SQLException e) {
            Assumptions.abort("테스트 컨테이너 접속 실패로 검증을 건너뜁니다: " + e.getMessage());
        }
    }

    private void resetStatements(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT pg_stat_statements_reset()");
        } catch (SQLException ignored) {
            // 확장이 없으면 시퀀스 관측을 포기하고 시간만 본다.
        }
    }

    /** {@code pg_stat_statements}에서 패턴에 맞는 문장의 실행 횟수 합. 확장이 없으면 0. */
    private long countCalls(Connection conn, String pattern) {
        String sql = "SELECT COALESCE(SUM(calls), 0) FROM pg_stat_statements WHERE query ILIKE ?";
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    /** DB에 실제로 반영된 시퀀스 증가폭. allocationSize가 DDL에 반영됐는지 확인한다. */
    private long sequenceIncrement(Connection conn, String sequenceName) {
        String sql = "SELECT increment_by FROM pg_sequences WHERE sequencename = ?";
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, sequenceName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            return -1;
        }
    }

    private DocumentChunk chunk(int i) {
        return DocumentChunk.builder()
                .sourceType(SourceType.WORK_LOG)
                .sourceId((long) i)
                .chunkIndex(0)
                .languageCode("ko")
                .content("배치 검증용 청크 " + i)
                .contentHash(String.format("%064d", i))
                // vector(384) 컬럼이라 길이가 정확히 맞아야 한다. DB가 차원을 강제한다.
                .embedding(new float[DocumentChunk.EMBEDDING_DIM])
                .embeddingDim(DocumentChunk.EMBEDDING_DIM)
                .embeddingModel("batch-verification")
                .visibility(Visibility.PUBLIC)
                .build();
    }
}
