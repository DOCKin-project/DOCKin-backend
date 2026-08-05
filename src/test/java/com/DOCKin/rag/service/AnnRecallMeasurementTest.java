package com.DOCKin.rag.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ANN(HNSW) recall 실측 — ADR-0006 8-2가 남긴 미결 항목.
 *
 * <h3>왜 이 측정이 필요한가</h3>
 * HNSW 인덱스는 만들어져 있지만 <b>"켜도 되는가"에 답한 적이 없다.</b>
 * ANN은 이름 그대로 근사(approximate)라 정확 최근접이 놓치지 않는 문서를 놓칠 수 있고,
 * 그 손실이 얼마인지 모르면 <b>이 프로젝트가 스스로 경계한 "근거 없이 튜닝했다"</b>가 된다.
 *
 * <p>Phase 1 브루트포스가 <b>정확 최근접</b>이라는 점이 여기서 값을 한다 —
 * 같은 코퍼스에서 인덱스를 끄고 돌리면 그것이 곧 정답(ground truth)이다.
 * 별도의 라벨링이 필요 없다.
 *
 * <h3>ADR-0006 8-2가 남긴 구체적 의문</h3>
 * 합성 벡터로 잰 예비 측정에서 <b>권한 선필터를 걸면 HNSW가 무너졌다</b> —
 * 일반 사용자 경로에서 top-5를 요청했는데 1건만 돌아왔다. 기밀성이 아니라 <b>완전성</b>이
 * 깨지는 문제이며(필터는 여전히 SQL 안에서 걸린다), 그 결론을 실데이터 전까지 미뤄뒀다.
 * 이 테스트가 그 지점을 다시 본다.
 *
 * <h3>측정 방법</h3>
 * <ul>
 *   <li><b>정답</b> — {@code enable_indexscan=off}로 HNSW를 끄고 전체를 훑는다(정확 최근접)</li>
 *   <li><b>ANN</b> — 인덱스를 켠 채 같은 질의</li>
 *   <li><b>recall@k</b> — 두 결과 집합의 교집합 크기 / k</li>
 *   <li><b>지연</b> — {@code EXPLAIN ANALYZE}의 실행 시간(클라이언트 왕복 제외)</li>
 * </ul>
 *
 * <p>질의 벡터는 실제 TEI 호출로 만든다. 난수 벡터로는 recall을 잴 수 없다 —
 * 균등 난수는 서로 거의 직교해 최근접이라는 개념 자체가 흐려지기 때문이다
 * ({@code CorpusSeedGeneratorTest} 주석 참고).
 *
 * <pre>
 *   DB_PASSWORD=... RUN_ANN_RECALL=1 ./gradlew test --tests "*AnnRecallMeasurementTest"
 * </pre>
 */
class AnnRecallMeasurementTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/dockindb";
    private static final String EMBED_URL = "http://localhost:8081/embed";
    private static final String MODEL = "intfloat/multilingual-e5-small";
    private static final int TOP_K = 5;

    /** 생성 코퍼스의 어휘를 겨냥한 질의. 실제 검색에 가까운 문장으로 만든다. */
    private static final String[] QUERIES = {
            "용접기 와이어가 자꾸 멈추는데 원인이 뭔가요",
            "크레인 와이어로프 소선이 끊어졌을 때 조치",
            "유압 실린더에서 기름이 새는 경우",
            "베어링에서 소음이 커졌습니다",
            "절연 저하로 차단기가 떨어졌어요",
            "냉각수 부족하면 어떤 증상이 나타나나요",
            "필터가 막히면 어떻게 되나",
            "제어 파라미터를 잘못 설정한 사례",
            "고소작업 안전대 착용 규정",
            "밀폐공간 진입 전 산소 농도 측정",
            "도장 작업 유기용제 마스크",
            "체결 볼트가 풀려서 진동이 발생",
            "집진 설비 점검 이력",
            "지게차 과전류 차단 사례",
            "발전기 기동 실패 원인",
            "공기압축기 온도 상승",
            "이송 컨베이어 정지 문제",
            "센서 접점 불량 교체",
            "개스킷 경화로 누유 발생",
            "감속기 이상 진동 조치",
    };

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    @DisplayName("ANN(HNSW) recall 및 권한 선필터 영향 실측")
    void ann_recall_실측() throws Exception {
        String password = System.getenv("DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "DB_PASSWORD가 없어 건너뜁니다.");
        Assumptions.assumeTrue(System.getenv("RUN_ANN_RECALL") != null,
                "RUN_ANN_RECALL이 없어 건너뜁니다. 코퍼스 색인이 끝난 뒤에만 의미가 있습니다.");

        try (Connection conn = DriverManager.getConnection(URL, "root", password)) {
            int total = count(conn, "SELECT COUNT(*) FROM document_chunks WHERE embedding_model = '" + MODEL + "'");
            Assumptions.assumeTrue(total > 1000,
                    "청크가 " + total + "건뿐이라 ANN 측정이 의미가 없습니다. 색인을 먼저 끝내세요.");

            System.out.println();
            System.out.println("=== ANN recall 실측 (코퍼스 " + String.format("%,d", total) + " 청크 / top-" + TOP_K + ") ===");
            System.out.println();

            // 소유자별 청크 분포를 먼저 본다 — 권한 선필터가 후보를 얼마나 줄이는지가 해석의 전제다.
            printOwnerDistribution(conn, total);

            String owner = topOwner(conn);

            System.out.printf("%-4s | %-34s | %-9s | %-9s | %-9s | %-9s%n",
                    "#", "질의", "recall@5", "정확(ms)", "ANN(ms)", "배수");
            System.out.println("-".repeat(96));

            List<Double> recalls = new ArrayList<>();
            List<Double> exactMs = new ArrayList<>();
            List<Double> annMs = new ArrayList<>();

            for (int i = 0; i < QUERIES.length; i++) {
                String vec = literal(embedQuery(QUERIES[i]));
                String where = "embedding_model = '" + MODEL + "'";

                Set<Long> exact = search(conn, vec, where, false);
                Set<Long> ann = search(conn, vec, where, true);
                double recall = intersection(exact, ann) / (double) TOP_K;
                double e = explainMs(conn, vec, where, false);
                double a = explainMs(conn, vec, where, true);

                recalls.add(recall); exactMs.add(e); annMs.add(a);
                System.out.printf("%-4d | %-34s | %-9s | %-9.2f | %-9.2f | %-9s%n",
                        i + 1, trim(QUERIES[i], 32),
                        String.format("%d/%d", intersection(exact, ann), TOP_K), e, a,
                        a > 0 ? String.format("%.1fx", e / a) : "-");
            }

            System.out.println("-".repeat(96));
            System.out.printf("%-4s | %-34s | %-9.3f | %-9.2f | %-9.2f | %-9s%n",
                    "평균", "", avg(recalls), avg(exactMs), avg(annMs),
                    String.format("%.1fx", avg(exactMs) / Math.max(avg(annMs), 0.0001)));
            System.out.println();

            measureWithPermissionFilter(conn, owner);
            printPlan(conn, literal(embedQuery(QUERIES[0])));
        } catch (SQLException e) {
            Assumptions.abort("PostgreSQL 접속 실패로 건너뜁니다: " + e.getMessage());
        }
    }

    /**
     * ADR-0006 8-2가 남긴 미결 — 권한 선필터를 걸면 HNSW가 무너지는가.
     *
     * <p>HNSW는 그래프를 따라가며 후보를 좁히는데, 필터가 붙으면 <b>탐색한 후보 대부분이
     * 필터에 걸려 버려진다.</b> 그래서 요청한 k보다 적게 돌아올 수 있다.
     * pgvector의 {@code iterative_scan}은 부족하면 더 탐색하게 하는 옵션이다.
     */
    private void measureWithPermissionFilter(Connection conn, String owner) throws SQLException {
        String vec = literal(embedQuerySafe("용접기 와이어 송급 불량 조치"));
        String userWhere = "embedding_model = '" + MODEL + "' AND ("
                + "visibility = 'PUBLIC' OR (visibility = 'OWNER' AND owner_user_id = '" + owner + "'))";

        System.out.println("=== 권한 선필터를 걸었을 때 (일반 사용자 경로, owner=" + owner + ") ===");
        System.out.printf("%-30s | %-10s | %-10s%n", "방식", "반환 건수", "지연(ms)");
        System.out.println("-".repeat(56));

        Set<Long> exact = search(conn, vec, userWhere, false);
        System.out.printf("%-30s | %-10d | %-10.2f%n", "정확 최근접 + 선필터",
                exact.size(), explainMs(conn, vec, userWhere, false));

        Set<Long> ann = search(conn, vec, userWhere, true);
        System.out.printf("%-30s | %-10d | %-10.2f%n", "HNSW + 선필터",
                ann.size(), explainMs(conn, vec, userWhere, true));

        try (Statement st = conn.createStatement()) {
            st.execute("SET hnsw.iterative_scan = relaxed_order");
            Set<Long> iter = search(conn, vec, userWhere, true);
            System.out.printf("%-30s | %-10d | %-10.2f%n", "HNSW + iterative_scan",
                    iter.size(), explainMs(conn, vec, userWhere, true));
            System.out.printf("%-30s | %-10s | %s%n", "  └ 정확 대비 recall",
                    intersection(exact, iter) + "/" + exact.size(), "");
            st.execute("SET hnsw.iterative_scan = off");
        } catch (SQLException e) {
            System.out.println("(iterative_scan 미지원 pgvector 버전: " + e.getMessage() + ")");
        }
        System.out.println();
    }

    private void printOwnerDistribution(Connection conn, int total) throws SQLException {
        System.out.println("[코퍼스 구성]");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT visibility, COALESCE(owner_user_id,'-') AS owner, COUNT(*) c "
                             + "FROM document_chunks WHERE embedding_model='" + MODEL + "' "
                             + "GROUP BY 1,2 ORDER BY 3 DESC")) {
            while (rs.next()) {
                System.out.printf("  %-8s %-12s %,10d건 (%.1f%%)%n",
                        rs.getString(1), rs.getString(2), rs.getInt(3), 100.0 * rs.getInt(3) / total);
            }
        }
        System.out.println();
    }

    private String topOwner(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT owner_user_id FROM document_chunks WHERE owner_user_id IS NOT NULL "
                             + "GROUP BY 1 ORDER BY COUNT(*) DESC LIMIT 1")) {
            return rs.next() ? rs.getString(1) : "worker01";
        }
    }

    private void printPlan(Connection conn, String vec) throws SQLException {
        System.out.println("=== 실행 계획 (HNSW가 실제로 선택되는가) ===");
        try (Statement st = conn.createStatement()) {
            st.execute("SET enable_indexscan = on");
            try (ResultSet rs = st.executeQuery(
                    "EXPLAIN SELECT chunk_id FROM document_chunks WHERE embedding_model='" + MODEL + "' "
                            + "ORDER BY embedding <=> '" + vec + "'::vector LIMIT " + TOP_K)) {
                while (rs.next()) {
                    System.out.println("  " + rs.getString(1));
                }
            }
        }
        System.out.println();
    }

    // ------------------------------------------------------------------

    /** {@code useIndex=false}면 HNSW를 끄고 전체를 훑는다 = 정확 최근접 = 정답. */
    private Set<Long> search(Connection conn, String vec, String where, boolean useIndex) throws SQLException {
        Set<Long> ids = new LinkedHashSet<>();
        try (Statement st = conn.createStatement()) {
            st.execute("SET enable_indexscan = " + (useIndex ? "on" : "off"));
            try (ResultSet rs = st.executeQuery(sql(vec, where))) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
            st.execute("SET enable_indexscan = on");
        }
        return ids;
    }

    private double explainMs(Connection conn, String vec, String where, boolean useIndex) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("SET enable_indexscan = " + (useIndex ? "on" : "off"));
            // 워밍 1회 후 측정 — 첫 실행은 캐시 미스가 섞인다.
            st.executeQuery(sql(vec, where)).close();
            double best = Double.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                try (ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE, TIMING ON) " + sql(vec, where))) {
                    while (rs.next()) {
                        String line = rs.getString(1);
                        if (line.startsWith("Execution Time:")) {
                            best = Math.min(best, Double.parseDouble(
                                    line.replace("Execution Time:", "").replace("ms", "").trim()));
                        }
                    }
                }
            }
            st.execute("SET enable_indexscan = on");
            return best == Double.MAX_VALUE ? -1 : best;
        }
    }

    private String sql(String vec, String where) {
        return "SELECT chunk_id FROM document_chunks WHERE " + where
                + " ORDER BY embedding <=> '" + vec + "'::vector LIMIT " + TOP_K;
    }

    private int count(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int intersection(Set<Long> a, Set<Long> b) {
        int n = 0;
        for (Long x : a) {
            if (b.contains(x)) n++;
        }
        return n;
    }

    private double avg(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private String literal(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    /** e5 규칙대로 질의에는 {@code query:} 프리픽스를 붙인다. */
    private float[] embedQuery(String text) throws IOException, InterruptedException {
        String body = "{\"inputs\":\"query: " + text.replace("\"", "\\\"") + "\"}";
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(EMBED_URL))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String s = res.body();
        String inner = s.substring(s.indexOf('[', 1) + 1, s.lastIndexOf(']', s.length() - 2));
        String[] parts = inner.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return v;
    }

    private float[] embedQuerySafe(String text) {
        try {
            return embedQuery(text);
        } catch (Exception e) {
            throw new IllegalStateException("임베딩 서버 호출 실패: " + e.getMessage(), e);
        }
    }
}
