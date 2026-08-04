package com.DOCKin.rag.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * P1-10 실측: Phase 1 브루트포스(정확 최근접) 검색의 지연시간과 메모리 사용량.
 *
 * <h3>왜 합성 벡터로 재는가</h3>
 * 검색 지연시간은 <b>후보 개수와 차원 수</b>에만 좌우되고 벡터 값의 의미와는 무관하다.
 * 10만 건을 실제 임베딩하려면 약 20분이 걸리지만(12ms/건 실측), 지연시간 측정에는 필요 없다.
 * 다만 <b>검색 품질(recall/정확도)은 이 방식으로 잴 수 없다</b> — 그건 별도 항목이다.
 *
 * <h3>측정 조건</h3>
 * 실제 MySQL 8.0 컨테이너(`dockin-db`, localhost:3308)를 대상으로 한다.
 * DB가 없으면 테스트는 <b>실패가 아니라 skip</b> 된다 — 일반 빌드를 깨뜨리지 않기 위함이다.
 *
 * <p>실행: {@code DB_PASSWORD=... ./gradlew test --tests "*BruteForceSearchBenchmarkTest"}
 */
class BruteForceSearchBenchmarkTest {

    private static final String URL =
            "jdbc:mysql://localhost:3308/dockindb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String TABLE = "bench_document_chunks";

    /** 실제 모델(multilingual-e5-small) 차원 */
    private static final int DIM = 384;
    private static final int TOP_K = 5;
    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 5;

    /** 측정 규모. 100만은 Xmx400M에서 적재 불가이므로 제외한다(SERVICE-SCALE-ASSUMPTIONS 3-2). */
    private static final int[] SCALES = {10_000, 100_000};

    @Test
    @DisplayName("브루트포스 검색 지연시간 - 1만 / 10만 청크")
    void 브루트포스_검색_실측() throws Exception {
        String password = System.getenv("DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "DB_PASSWORD 환경변수가 없어 벤치마크를 건너뜁니다.");

        try (Connection conn = DriverManager.getConnection(URL, "root", password)) {
            System.out.println();
            System.out.println("=== 브루트포스 검색 실측 (MySQL 8.0 / " + DIM + "차원 / top-" + TOP_K + ") ===");
            System.out.printf("%-10s | %-22s | %-10s | %-11s | %-11s | %-11s | %-9s%n",
                    "적재 청크", "경로", "후보 수", "조회(ms)", "계산(ms)", "합계(ms)", "벡터(MB)");
            System.out.println("-".repeat(105));

            for (int scale : SCALES) {
                prepareTable(conn, scale);

                // 두 경로를 모두 잰다.
                //  - 일반 사용자: 권한 선필터가 후보를 크게 줄인다(현실적인 평균 경로)
                //  - 관리자: 필터가 없어 전체를 스캔한다(최악 케이스 = Phase 2 전환 판단 기준)
                for (boolean admin : new boolean[]{false, true}) {
                    Result result = measure(conn, admin);
                    System.out.printf("%-10s | %-22s | %-10s | %-11.1f | %-11.1f | %-11.1f | %-9.1f%n",
                            String.format("%,d", scale),
                            admin ? "관리자(전체 스캔)" : "일반 사용자(선필터)",
                            String.format("%,d", result.candidates),
                            result.fetchMs, result.computeMs, result.totalMs, result.vectorMb);
                }
            }
            System.out.println();

            dropTable(conn);
        } catch (SQLException e) {
            Assumptions.abort("MySQL(localhost:3308)에 접속할 수 없어 벤치마크를 건너뜁니다: " + e.getMessage());
        }
    }

    /** 실제 스키마와 동일한 형태로 만든다. VARBINARY 인라인 저장 여부가 스캔 성능에 영향을 준다. */
    private void prepareTable(Connection conn, int rows) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TABLE);
            st.execute("""
                    CREATE TABLE %s (
                        chunk_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        source_type VARCHAR(32) NOT NULL,
                        source_id BIGINT NOT NULL,
                        embedding VARBINARY(4096) NOT NULL,
                        embedding_dim INT NOT NULL,
                        visibility VARCHAR(16) NOT NULL,
                        owner_user_id VARCHAR(50) NULL,
                        INDEX idx_visibility (visibility, owner_user_id)
                    )
                    """.formatted(TABLE));
        }

        Random random = new Random(42); // 재현 가능하도록 시드 고정
        String sql = "INSERT INTO " + TABLE
                + " (source_type, source_id, embedding, embedding_dim, visibility, owner_user_id) VALUES (?,?,?,?,?,?)";

        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < rows; i++) {
                ps.setString(1, "WORK_LOG");
                ps.setLong(2, i);
                ps.setBytes(3, EmbeddingClient.toBytes(randomUnitVector(random)));
                ps.setInt(4, DIM);
                // 실제 분포를 흉내낸다. 안전교육 등 공개 문서가 일부, 나머지는 작성자 제한.
                ps.setString(5, i % 10 == 0 ? "PUBLIC" : "OWNER");
                ps.setString(6, i % 10 == 0 ? null : "user" + (i % 5000));
                ps.addBatch();
                if (i % 1000 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        conn.commit();
        conn.setAutoCommit(true);
    }

    private Result measure(Connection conn, boolean admin) throws SQLException {
        Random random = new Random(7);
        float[] query = randomUnitVector(random);

        for (int i = 0; i < WARMUP_RUNS; i++) {
            runOnce(conn, query, admin);
        }

        double fetchTotal = 0, computeTotal = 0;
        long vectorBytes = 0;
        int candidates = 0;

        for (int i = 0; i < MEASURED_RUNS; i++) {
            Timing timing = runOnce(conn, query, admin);
            fetchTotal += timing.fetchMs;
            computeTotal += timing.computeMs;
            vectorBytes = timing.vectorBytes;
            candidates = timing.candidates;
        }

        return new Result(
                candidates,
                fetchTotal / MEASURED_RUNS,
                computeTotal / MEASURED_RUNS,
                (fetchTotal + computeTotal) / MEASURED_RUNS,
                vectorBytes / 1024.0 / 1024.0);
    }

    /**
     * 실제 검색 경로를 그대로 재현한다.
     * 권한 선필터(SQL) → 벡터만 투영해 조회 → 코사인 계산 → top-k.
     * 본문은 최종 top-k에 대해서만 읽으므로 이 측정에 포함하지 않는다.
     */
    private Timing runOnce(Connection conn, float[] query, boolean admin) throws SQLException {
        String sql = "SELECT chunk_id, source_id, embedding, embedding_dim FROM " + TABLE
                + (admin ? "" : " WHERE visibility = 'PUBLIC' OR owner_user_id = ?");

        long fetchStart = System.nanoTime();
        List<byte[]> vectors = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        long vectorBytes = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (!admin) {
                ps.setString(1, "user1");
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] embedding = rs.getBytes("embedding");
                    vectors.add(embedding);
                    ids.add(rs.getLong("chunk_id"));
                    vectorBytes += embedding.length;
                }
            }
        }
        long fetchEnd = System.nanoTime();

        List<Scored> scored = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            double similarity = RetrievalService.cosine(query, EmbeddingClient.toFloats(vectors.get(i)));
            scored.add(new Scored(ids.get(i), similarity));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Scored> top = scored.subList(0, Math.min(TOP_K, scored.size()));
        long computeEnd = System.nanoTime();

        if (top.isEmpty()) {
            throw new IllegalStateException("검색 결과가 비어 측정이 무의미합니다.");
        }
        return new Timing(
                (fetchEnd - fetchStart) / 1_000_000.0,
                (computeEnd - fetchEnd) / 1_000_000.0,
                vectorBytes,
                vectors.size());
    }

    private void dropTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TABLE);
        }
    }

    /** 정규화된 랜덤 벡터. 실제 임베딩과 같은 크기·분포 특성을 갖게 한다. */
    private static float[] randomUnitVector(Random random) {
        float[] vector = new float[DIM];
        double norm = 0;
        for (int i = 0; i < DIM; i++) {
            vector[i] = (float) random.nextGaussian();
            norm += vector[i] * vector[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < DIM; i++) {
            vector[i] /= (float) norm;
        }
        return vector;
    }

    private record Timing(double fetchMs, double computeMs, long vectorBytes, int candidates) {}

    private record Result(int candidates, double fetchMs, double computeMs,
                          double totalMs, double vectorMb) {}

    private record Scored(Long chunkId, double score) {}
}
