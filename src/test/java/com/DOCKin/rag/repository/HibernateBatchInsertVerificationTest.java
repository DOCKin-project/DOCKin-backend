package com.DOCKin.rag.repository;

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

/**
 * 검증: {@code hibernate.jdbc.batch_size} 설정이 {@link DocumentChunk} INSERT에 실제로 적용되는가?
 *
 * <h3>왜 의심하는가</h3>
 * {@code DocumentChunk.chunkId}가 {@code GenerationType.IDENTITY}다.
 * Hibernate는 IDENTITY 전략에서 INSERT 직후 생성된 키를 읽어야 하므로 <b>JDBC 배치를 비활성화</b>하는
 * 것으로 알려져 있다. 설정을 켰다는 사실만으로 배치가 동작한다고 가정하면 안 된다
 * (앞서 인덱스도 `EXPLAIN`으로 확인한 것과 같은 이유).
 *
 * <h3>측정 방법</h3>
 * MySQL의 {@code Com_insert} 상태 변수는 <b>실제로 실행된 INSERT 문장 수</b>를 센다.
 * {@code rewriteBatchedStatements=true}에서 배치는 다중행 INSERT 한 문장으로 합쳐지므로,
 * 10,000건을 100건씩 저장했을 때:
 * <ul>
 *   <li>배치 동작 → 약 <b>100</b> 문장</li>
 *   <li>배치 미동작 → 약 <b>10,000</b> 문장</li>
 * </ul>
 * 추정이 아니라 DB가 직접 센 값이라 해석의 여지가 없다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3308/dockindb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true",
        "spring.datasource.username=root",
        "spring.datasource.password=${DB_PASSWORD:}",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.properties.hibernate.jdbc.batch_size=100",
        "spring.jpa.properties.hibernate.order_inserts=true",
        "spring.jpa.properties.hibernate.order_updates=true"
})
class HibernateBatchInsertVerificationTest {

    private static final String URL =
            "jdbc:mysql://localhost:3308/dockindb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final int TOTAL = 10_000;
    private static final int FLUSH_EVERY = 100;

    @Autowired
    private DocumentChunkRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("IDENTITY 전략에서 JDBC 배치 INSERT가 실제로 동작하는지")
    void 배치_적용_여부_검증() throws Exception {
        String password = System.getenv("DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "DB_PASSWORD 환경변수가 없어 검증을 건너뜁니다.");

        try (Connection status = DriverManager.getConnection(URL, "root", password)) {
            long before = comInsert(status);
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
            long after = comInsert(status);
            long statements = after - before;

            double perStatement = (double) TOTAL / Math.max(statements, 1);
            boolean batched = statements < TOTAL / 2;

            System.out.println();
            System.out.println("=== Hibernate 배치 INSERT 검증 (IDENTITY 전략) ===");
            System.out.printf("저장 건수          : %,d%n", TOTAL);
            System.out.printf("flush 주기         : %d건%n", FLUSH_EVERY);
            System.out.printf("실행된 INSERT 문장 : %,d  (Com_insert 증가분)%n", statements);
            System.out.printf("문장당 행 수       : %.1f%n", perStatement);
            System.out.printf("소요 시간          : %,d ms%n", elapsedMs);
            System.out.println();
            System.out.println(batched
                    ? ">>> 배치 동작함. batch_size 설정이 유효하다."
                    : ">>> 배치 미동작. IDENTITY 전략이 JDBC 배치를 막고 있다 "
                      + "-> JdbcTemplate 벌크 INSERT 등 대안이 필요하다.");
            System.out.println();
        } catch (SQLException e) {
            Assumptions.abort("MySQL(localhost:3308) 접속 실패로 검증을 건너뜁니다: " + e.getMessage());
        }
    }

    /** 실제로 실행된 INSERT 문장 수. 다중행 INSERT는 1로 센다. */
    private long comInsert(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW GLOBAL STATUS LIKE 'Com_insert'")) {
            return rs.next() ? Long.parseLong(rs.getString(2)) : -1;
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
                .embedding(new byte[1536])
                .embeddingDim(384)
                .embeddingModel("batch-verification")
                .visibility(Visibility.PUBLIC)
                .build();
    }
}
