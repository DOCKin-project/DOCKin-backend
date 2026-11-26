package com.DOCKin.rag.service;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 검색 품질(관련성) 측정 하네스 — {@code docs/SEARCH-QUALITY-EVAL-PLAN.md}, 이슈 #170.
 *
 * <h3>무엇을 하나 — 한 번에 끝까지</h3>
 * <ol>
 *   <li>테스트 컨테이너(사용자의 개발 DB가 아니다)에 {@link CorpusSeedGeneratorTest#seed}로 코퍼스를 만든다. 시드 고정</li>
 *   <li>{@link IndexingService#indexAll}로 실제 임베딩 서버(TEI)를 불러 색인한다</li>
 *   <li>{@code docs/eval/search-quality/queries.tsv}의 질문마다 정답 일지를 SQL로 계산한다(README "정답 조건")</li>
 *   <li>검색 방식별로 상위 결과를 받아 Hit@5·MRR@10·P@5를 내고, 질문별 원 결과를 TSV로 남긴다</li>
 * </ol>
 *
 * <h3>검색 방식 — 전부 관리자 경로(권한 필터 없음, 계획 5절)</h3>
 * <ul>
 *   <li>{@code ann-default} — 운영이 지금 실제로 도는 값. {@code ef_search} 40, {@code iterative_scan} off (#180)</li>
 *   <li>{@code ann-decided} — ADR-0006 8-4가 정했으나 코드에 없는 값. 100, {@code relaxed_order} (#180)</li>
 *   <li>{@code exact} — 인덱스를 끈 정확 최근접 (E3)</li>
 *   <li>{@code ann-no-translation} — {@code ann-decided}에서 번역본 청크를 뺀 것 (E2)</li>
 *   <li>{@code keyword} — 임베딩 장애 시 폴백과 같은 LIKE 검색 (E4)</li>
 *   <li>{@code svc-*} — 하네스 SQL이 아니라 <b>운영 코드 {@code RetrievalService.search}를 그대로</b> 부른다(관리자 경로).
 *       플래그만 바꿔 가며: {@code svc-vector}(둘 다 끔), {@code svc-hybrid}(#210), {@code svc-rerank}·{@code svc-hybrid-rerank}(#213).
 *       재순위는 {@code SEARCH_EVAL_RERANK_URL}이 있을 때만 돈다. {@code svc-vector}와 {@code ann-decided}가 같은지가 경로 검증이다</li>
 * </ul>
 * 벡터 SQL은 {@code NearestChunkJdbcRepository.findNearestAll}과 같은 꼴이고 후보 수도 운영과 같다(topK 5 × 4 = 20).
 * 청크를 원본 일지 단위로 묶어(번역본은 원문 일지로) 순위를 매긴다.
 *
 * <h3>실행</h3>
 * <pre>
 * # 임베딩 서버(포트는 아무거나). 개발용 dockin-embedding과 같은 이미지·모델.
 * docker run -d --name eval-embed -p 127.0.0.1:58081:80 -v dockin-spring_embedding_cache:/data \
 *   ghcr.io/huggingface/text-embeddings-inference:cpu-latest --model-id=intfloat/multilingual-e5-small --auto-truncate
 * RUN_SEARCH_EVAL=1 SEARCH_EVAL_EMBED_URL=http://localhost:58081 SEARCH_EVAL_CORPUS=20000 \
 *   ./gradlew test --tests "*SearchQualityEvalTest"
 *
 * # 재순위까지 재려면(#213). 모델은 compose.rerank.yaml과 같다.
 * docker run -d --name eval-rerank -p 127.0.0.1:58082:80 -v dockin-spring_reranker_cache:/data \
 *   ghcr.io/huggingface/text-embeddings-inference:cpu-latest --model-id=BAAI/bge-reranker-v2-m3 --auto-truncate
 * SEARCH_EVAL_RERANK_URL=http://localhost:58082 (위 명령에 더한다)
 * </pre>
 * 산출물은 {@code build/search-eval/}. 결과 해석은 계획 문서에 덧붙인다.
 *
 * <p>{@code SEARCH_EVAL_HNSW_BUILDS=N}(기본 1)이면 HNSW 인덱스를 N번 다시 지어 ANN 방식만 다시 묻고,
 * 회차별 분포와 같은 인덱스 안에서 짝지은 #180 차이를 따로 한 절에 쓴다(#187). 인덱스를 한 번만 지은
 * 결과로 ANN 설정끼리 비교하면, 재구축만으로 생기는 흔들림(로컬 8회에서 en 0.30~0.50)과 구별되지 않는다.
 */
@SpringBootTest(properties = {
        // 측정 도중 새벽 배치가 끼어들지 않게
        "rag.indexing.cron=-"
})
@DisplayName("검색 품질 하네스 (#170) - RUN_SEARCH_EVAL이 있을 때만")
class SearchQualityEvalTest extends ContainerTestSupport {

    static final Path QUERIES = Path.of("docs/eval/search-quality/queries.tsv");
    private static final Path OUT = Path.of("build/search-eval");
    private static final String MODEL = "intfloat/multilingual-e5-small";
    private static final int TOP_K = 5;
    private static final int CANDIDATES = TOP_K * 4;
    private static final int BOOTSTRAP = 2000;

    /**
     * 답 없는 질문(N)이 정말 답이 없는지 확인할 핵심 단어. 한국어 원문에 하나라도 나오면 그 질문은 N에서 뺀다(README "N 유형 확인").
     */
    static final Map<String, List<String>> N_ABSENT_TERMS = Map.ofEntries(
            // 파일럿(E0)
            Map.entry("n01", List.of("로봇", "티칭")),
            Map.entry("n02", List.of("비계")),
            Map.entry("n03", List.of("방독", "마스크")),
            Map.entry("n04", List.of("단가", "구매")),
            Map.entry("n05", List.of("정격", "하중")),
            Map.entry("n06", List.of("자격", "면허")),
            // E1 확장 — N1 범위 밖
            Map.entry("n07", List.of("탑재")),
            Map.entry("n08", List.of("밀폐", "산소")),
            Map.entry("n09", List.of("신호수", "수신호")),
            Map.entry("n10", List.of("안전모")),
            Map.entry("n11", List.of("소화기")),
            Map.entry("n12", List.of("도막", "두께")),
            Map.entry("n13", List.of("수당")),
            // E1 확장 — N2 있는 설비의 없는 정보
            Map.entry("n14", List.of("전력")),
            Map.entry("n15", List.of("연료")),
            Map.entry("n16", List.of("제조사")),
            Map.entry("n17", List.of("보증")),
            Map.entry("n18", List.of("설치")),
            Map.entry("n19", List.of("처리량")),
            Map.entry("n20", List.of("미세먼지", "농도")));

    private static final List<String> METHODS =
            List.of("ann-default", "ann-decided", "exact", "ann-no-translation", "keyword", "keyword-ranked",
                    "svc-vector", "svc-hybrid", "svc-rerank", "svc-hybrid-rerank");

    /** 운영 코드 경로({@code RetrievalService.search}). 이름 → {하이브리드, 재순위}. */
    private static final Map<String, boolean[]> SVC_METHODS = Map.of(
            "svc-vector", new boolean[]{false, false},
            "svc-hybrid", new boolean[]{true, false},
            "svc-rerank", new boolean[]{false, true},
            "svc-hybrid-rerank", new boolean[]{true, true});

    /** HNSW 인덱스를 타는 방식. 재구축마다 다시 묻는 것은 이것뿐이다(#187). */
    private static final List<String> ANN_METHODS = List.of("ann-default", "ann-decided", "ann-no-translation");

    /** V1 마이그레이션이 만드는 인덱스. */
    private static final String HNSW_INDEX = "idx_chunk_embedding_hnsw";

    @DynamicPropertySource
    static void embedding(DynamicPropertyRegistry registry) {
        registry.add("external-api.embedding.base-url", SearchQualityEvalTest::embedUrl);
        registry.add("external-api.reranker.base-url",
                () -> rerankUrl() == null ? "http://localhost:1" : rerankUrl());
        // 재순위 20개가 CPU에서 5초를 넘을 수 있다. 측정에서는 타임아웃으로 조용히 재순위가 빠지면 안 된다.
        registry.add("external-api.reranker.response-timeout-ms", () -> "60000");
    }

    /** 재순위 서버. 없으면 svc-rerank·svc-hybrid-rerank를 건너뛴다. */
    static String rerankUrl() {
        String v = System.getenv("SEARCH_EVAL_RERANK_URL");
        return v == null || v.isBlank() ? null : v.trim();
    }

    static String embedUrl() {
        String v = System.getenv("SEARCH_EVAL_EMBED_URL");
        return v == null || v.isBlank() ? "http://localhost:58081" : v.trim();
    }

    @Autowired private DataSource dataSource;
    @Autowired private IndexingService indexingService;
    @Autowired private EmbeddingClient embeddingClient;
    @Autowired private RetrievalService retrievalService;
    @Autowired private RerankClient rerankClient;

    // ------------------------------------------------------------------

    @Test
    void 검색_품질_측정() throws Exception {
        Assumptions.assumeTrue(System.getenv("RUN_SEARCH_EVAL") != null,
                "RUN_SEARCH_EVAL이 없어 건너뜁니다. 코퍼스 생성·색인에 수 분이 걸리는 측정입니다.");
        Assumptions.assumeTrue(embeddingUp(), "임베딩 서버(" + embedUrl() + ")가 없어 건너뜁니다.");
        int corpus = Integer.parseInt(System.getenv().getOrDefault("SEARCH_EVAL_CORPUS", "20000").trim());
        Files.createDirectories(OUT);

        // 1. 코퍼스와 색인 — 캐시가 있으면 불러온다. CPU TEI로 2,000건 색인이 36분이었다(청크당 약 0.6초).
        //    질문을 고쳐 다시 돌릴 때마다 색인을 반복하지 않게, 처음 색인한 결과를 테이블째 파일로 남긴다.
        Path cache = cacheDir().resolve("corpus-" + corpus);
        long indexMs;
        if (Files.isDirectory(cache)) {
            long t0 = System.currentTimeMillis();
            try (Connection conn = dataSource.getConnection()) {
                restore(conn, cache);
            }
            indexMs = -(System.currentTimeMillis() - t0);   // 음수 = 캐시에서 불러온 시간
        } else {
            try (Connection conn = dataSource.getConnection()) {
                seedEquipment(conn);
                new CorpusSeedGeneratorTest().seed(conn, corpus);
            }
            long t0 = System.currentTimeMillis();
            IndexingService.IndexRun run = indexingService.indexAll();
            if (!run.completed()) {
                throw new IllegalStateException("색인이 끝나지 않았습니다: " + run.abortReason());
            }
            indexMs = System.currentTimeMillis() - t0;
            try (Connection conn = dataSource.getConnection()) {
                dump(conn, cache);
            }
        }

        try (Connection conn = dataSource.getConnection()) {
            Conditions cond = conditions(conn, corpus, indexMs);
            List<Query> queries = loadQueries();
            Map<Long, Long> translationToLog = translationToLog(conn);

            // 2. 정답
            Map<String, Set<Long>> labels = new LinkedHashMap<>();
            Map<String, String> nExcluded = new LinkedHashMap<>();
            for (Query q : queries) {
                if (!q.lang.equals("ko") || labels.containsKey(q.qid)) {
                    continue;
                }
                if (q.noAnswer) {
                    String hit = presentTerm(conn, N_ABSENT_TERMS.getOrDefault(q.qid, List.of()));
                    if (hit != null) {
                        nExcluded.put(q.qid, hit);
                    }
                    labels.put(q.qid, Set.of());
                } else {
                    labels.put(q.qid, relevant(conn, q));
                }
            }

            // 3. 검색. 질의 벡터는 한 번만 만든다 — 재구축마다 같은 벡터로 물어야 인덱스 차이만 남는다.
            Map<Query, String> literals = new LinkedHashMap<>();
            for (Query q : queries) {
                if (!(q.noAnswer && nExcluded.containsKey(q.qid))) {
                    literals.put(q, RetrievalService.toVectorLiteral(embeddingClient.embedQuery(q.query)));
                }
            }
            // HNSW 재구축 N회(#187). 1이면 불러온(또는 색인하며 쌓인) 인덱스 그대로 한 번.
            // 2 이상이면 매번 REINDEX로 새로 짓고 ANN 방식만 다시 묻는다. 정확·키워드는 인덱스와 무관해 한 번만.
            int builds = hnswBuilds();
            List<Long> reindexMs = new ArrayList<>();
            List<Result> results = new ArrayList<>();
            latencyMs.clear();
            if (rerankUrl() != null && rerankClient.score("warmup", List.of("warmup")).isEmpty()) {
                throw new IllegalStateException("재순위 서버(" + rerankUrl() + ")가 응답하지 않습니다.");
            }
            for (int b = 1; b <= builds; b++) {
                if (builds > 1) {
                    reindexMs.add(reindexHnsw(conn));
                }
                for (var e : literals.entrySet()) {
                    Query q = e.getKey();
                    for (String method : METHODS) {
                        if (b > 1 && !ANN_METHODS.contains(method)) {
                            continue;
                        }
                        if (SVC_METHODS.containsKey(method) && SVC_METHODS.get(method)[1] && rerankUrl() == null) {
                            continue;
                        }
                        long t0 = System.nanoTime();
                        List<Ranked> ranked = switch (method) {
                            case "keyword" -> keyword(conn, q.query, translationToLog);
                            case "keyword-ranked" -> keywordRanked(conn, q.query, translationToLog);
                            case "svc-vector", "svc-hybrid", "svc-rerank", "svc-hybrid-rerank" ->
                                    service(method, q.query, translationToLog);
                            default -> vector(conn, method, e.getValue(), translationToLog);
                        };
                        latencyMs.computeIfAbsent(method, k -> new ArrayList<>())
                                .add((System.nanoTime() - t0) / 1_000_000.0);
                        results.add(new Result(q, method, ranked, labels.get(q.qid), b));
                    }
                }
            }

            // 4. 기록. 본문 표는 1회차 인덱스 기준이고, 재구축 분포는 따로 한 절.
            writePerQuery(results);
            List<Result> first = results.stream().filter(r -> r.build == 1).toList();
            String summary = summarize(cond, queries, labels, nExcluded, first)
                    + (builds > 1 ? rebuildSection(results, builds, reindexMs) : "");
            Files.writeString(OUT.resolve("summary.md"), summary, StandardCharsets.UTF_8);
            System.out.println(summary);
        }
    }

    // ------------------------------------------------------------------
    // 준비
    // ------------------------------------------------------------------

    /** 코퍼스 캐시 위치. {@code build/} 밖이 기본이다 — {@code gradlew clean}에 몇 시간짜리 색인이 지워지지 않게. */
    static Path cacheDir() {
        String v = System.getenv("SEARCH_EVAL_CACHE");
        return v == null || v.isBlank()
                ? Path.of(System.getProperty("user.home"), ".dockin-search-eval")
                : Path.of(v.trim());
    }

    /** 코퍼스를 이루는 테이블. 복원은 이 순서대로 — FK 부모가 먼저다. */
    static final List<String> CORPUS_TABLES =
            List.of("equipment", "users", "work_logs", "work_log_translations", "document_chunks");

    /**
     * 테이블을 PostgreSQL {@code COPY} 텍스트 형식으로 파일에 쓴다. 벡터도 텍스트 표현 그대로 왕복한다.
     *
     * <p><b>컬럼 이름을 같이 남긴다</b>({@code <table>.columns}). 컬럼 목록 없이 {@code COPY table}로 쓰면
     * 캐시를 만든 뒤 마이그레이션이 컬럼을 하나만 더해도(V20 {@code users.deleted_at}) 복원이
     * "missing data for column"으로 죽는다 — 2026-11-26 E1 첫 실행이 그랬다. 이름을 알면 새 컬럼은 기본값으로 채워진다.
     */
    void dump(Connection conn, Path dir) throws SQLException, IOException {
        Path tmp = dir.resolveSibling(dir.getFileName() + ".tmp");
        Files.createDirectories(tmp);
        org.postgresql.copy.CopyManager copy = conn.unwrap(org.postgresql.PGConnection.class).getCopyAPI();
        for (String table : CORPUS_TABLES) {
            List<String> columns = columnsOf(conn, table);
            Files.writeString(tmp.resolve(table + ".columns"), String.join(",", columns), StandardCharsets.UTF_8);
            try (var out = Files.newOutputStream(tmp.resolve(table + ".copy"))) {
                copy.copyOut("COPY " + table + " (" + String.join(", ", columns) + ") TO STDOUT", out);
            }
        }
        Files.move(tmp, dir);   // 다 쓴 뒤에만 이름을 바꾼다 — 중간에 죽으면 반쪽 캐시를 쓰지 않는다
    }

    /** 테이블의 컬럼 이름, 정의 순서대로. */
    private static List<String> columnsOf(Connection conn, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ?
                ORDER BY ordinal_position
                """)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
        }
        return columns;
    }

    /**
     * 캐시를 빈 테스트 컨테이너에 붓는다. 같은 JVM의 다른 테스트가 남긴 행과 섞이지 않게 비우고 넣는다 —
     * 이 하네스는 단독 실행({@code --tests "*SearchQualityEvalTest"})을 전제로 한다.
     */
    void restore(Connection conn, Path dir) throws SQLException, IOException {
        org.postgresql.copy.CopyManager copy = conn.unwrap(org.postgresql.PGConnection.class).getCopyAPI();
        try (Statement st = conn.createStatement()) {
            st.execute("TRUNCATE " + String.join(", ", CORPUS_TABLES) + " CASCADE");
        }
        for (String table : CORPUS_TABLES) {
            Path columns = dir.resolve(table + ".columns");
            if (!Files.exists(columns)) {
                throw new IllegalStateException("코퍼스 캐시 " + dir + "가 컬럼 목록 없이 만들어진 옛 형식이다. "
                        + "그 뒤 마이그레이션이 컬럼을 더했으면 복원이 깨진다. 캐시 디렉터리를 옮기거나 지우고 다시 돌리면 새로 만든다(색인 15분 안팎).");
            }
            String list = Files.readString(columns, StandardCharsets.UTF_8).trim();
            try (var in = Files.newInputStream(dir.resolve(table + ".copy"))) {
                copy.copyIn("COPY " + table + " (" + list + ") FROM STDIN", in);
            }
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ANALYZE document_chunks");
        }
    }

    /** {@code SEARCH_EVAL_HNSW_BUILDS}. 기본 1(재구축 없음). */
    private static int hnswBuilds() {
        String v = System.getenv("SEARCH_EVAL_HNSW_BUILDS");
        return v == null || v.isBlank() ? 1 : Math.max(1, Integer.parseInt(v.trim()));
    }

    /**
     * HNSW 인덱스를 처음부터 다시 짓는다. 걸린 밀리초를 돌려준다.
     *
     * <p>pgvector는 원소마다 층을 난수로 정하므로 같은 데이터로 지어도 그래프가 달라진다.
     * 그 난수는 SQL {@code setseed()}로 고정되지 않는다 — 그래서 고정하는 대신 여러 번 지어 분포를 본다.
     * 병렬 빌드는 끈다: V1 주석대로 Docker /dev/shm 64MB를 넘기고, 직렬이 조건을 하나 줄인다.
     */
    private long reindexHnsw(Connection conn) throws SQLException {
        long t0 = System.currentTimeMillis();
        try (Statement st = conn.createStatement()) {
            st.execute("SET maintenance_work_mem = '256MB'");
            st.execute("SET max_parallel_maintenance_workers = 0");
            st.execute("REINDEX INDEX " + HNSW_INDEX);
            st.execute("RESET maintenance_work_mem");
            st.execute("RESET max_parallel_maintenance_workers");
        }
        return System.currentTimeMillis() - t0;
    }

    /** 생성기가 요구하는 장비 1~5번. R__seed_sample.sql과 같은 값 — 테스트 컨테이너는 seed 프로파일이 아니라 없다. */
    void seedEquipment(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO equipment (equipment_id, name, qr_code, nfc_tag) VALUES
                      (1, 'CO2 용접기 3호기', 'QR-WELD-003', 'NFC-WELD-003'),
                      (2, '겐트리 크레인 1호기', 'QR-CRANE-001', 'NFC-CRANE-001'),
                      (3, '무인 도장설비 A', 'QR-PAINT-A', 'NFC-PAINT-A'),
                      (4, '플라즈마 절단기 2호', 'QR-CUT-002', 'NFC-CUT-002'),
                      (5, '고소작업대 5호', 'QR-LIFT-005', 'NFC-LIFT-005')
                    ON CONFLICT DO NOTHING
                    """);
        }
    }

    boolean embeddingUp() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(embedUrl() + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private record Conditions(String text) {}

    private Conditions conditions(Connection conn, int corpus, long indexMs) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("| 항목 | 값 |\n|---|---|\n");
        sb.append("| 실행 | ").append(LocalDateTime.now().withNano(0)).append(" |\n");
        sb.append("| 생성 일지(SEARCH_EVAL_CORPUS) | ").append(String.format("%,d", corpus)).append(" |\n");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT source_type, count(*) FROM document_chunks GROUP BY 1 ORDER BY 1")) {
            while (rs.next()) {
                sb.append("| 청크 ").append(rs.getString(1)).append(" | ")
                        .append(String.format("%,d", rs.getLong(2))).append(" |\n");
            }
        }
        sb.append("| 색인 소요 | ").append(indexMs >= 0
                ? String.format("%,d초", indexMs / 1000)
                : String.format("캐시에서 불러옴(%,d초) — 색인은 이전 실행", -indexMs / 1000)).append(" |\n");
        sb.append("| 임베딩 모델 | ").append(MODEL).append(" (TEI, ").append(embedUrl()).append(") |\n");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT extversion FROM pg_extension WHERE extname = 'vector'")) {
            rs.next();
            sb.append("| pgvector | ").append(rs.getString(1)).append(" |\n");
        }
        sb.append("| 후보 수 | ").append(CANDIDATES).append("청크(운영과 같음) → 일지 단위로 묶어 순위 |\n");
        return new Conditions(sb.toString());
    }

    Map<Long, Long> translationToLog(Connection conn) throws SQLException {
        Map<Long, Long> map = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT translation_id, log_id FROM work_log_translations")) {
            while (rs.next()) {
                map.put(rs.getLong(1), rs.getLong(2));
            }
        }
        return map;
    }

    // ------------------------------------------------------------------
    // 질문과 정답
    // ------------------------------------------------------------------

    record Query(String qid, String type, String lang, String query,
                         String equipment, String unit, String part, String symptom,
                         String cause, String action, boolean noAnswer) {}

    List<Query> loadQueries() throws IOException {
        List<String> lines = Files.readAllLines(QUERIES, StandardCharsets.UTF_8);
        List<Query> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            line = line.replace("\r", "");
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            out.add(new Query(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], "1".equals(f[10])));
        }
        return out;
    }

    /**
     * 정답 일지. 원문 첫 문장 틀 {@code {설비} {호기}호기 {부품}에서 {증상}.}로 맞춘다(README "정답 조건").
     * 부품과 증상을 따로 LIKE로 걸지 않는다 — 긴 본문은 (부품1,증상1)(부품2,증상2)를 함께 담는다.
     */
    Set<Long> relevant(Connection conn, Query q) throws SQLException {
        List<String> likes = new ArrayList<>();
        if (!q.part.isEmpty()) {
            String unit = q.unit.isEmpty() ? "%" : q.unit;
            likes.add("%" + q.equipment + " " + unit + "호기 " + q.part + "에서"
                    + (q.symptom.isEmpty() ? "" : " " + q.symptom) + "%");
        } else {
            likes.add("%" + q.equipment + " %호기%");
            if (!q.symptom.isEmpty()) {
                likes.add("%에서 " + q.symptom + "%");
            }
        }
        if (!q.cause.isEmpty()) {
            likes.add("%" + q.cause + "%");
        }
        if (!q.action.isEmpty()) {
            likes.add("%" + q.action + "%");
        }
        String where = likes.stream().map(l -> "log_text LIKE ?").collect(Collectors.joining(" AND "));
        Set<Long> ids = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT log_id FROM work_logs WHERE " + where)) {
            for (int i = 0; i < likes.size(); i++) {
                ps.setString(i + 1, likes.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /** 단어 중 하나라도 한국어 원문(제목·본문)에 있으면 그 단어를, 없으면 null. */
    String presentTerm(Connection conn, List<String> terms) throws SQLException {
        for (String term : terms) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM work_logs WHERE log_text LIKE ? OR title LIKE ? LIMIT 1")) {
                ps.setString(1, "%" + term + "%");
                ps.setString(2, "%" + term + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return term;
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 검색
    // ------------------------------------------------------------------

    /** 일지 단위로 묶은 순위 항목. {@code logId}가 null이면 일지가 아닌 원본(안전교육 등)이다. */
    private record Ranked(String key, Long logId, double similarity) {}

    /** 방식별 검색 한 번의 시간(ms). 운영 경로는 질의 임베딩·재순위 호출까지 포함한다. */
    private final Map<String, List<Double>> latencyMs = new LinkedHashMap<>();

    /**
     * 운영 코드 그대로({@code RetrievalService.search}, 관리자 경로). 플래그만 방식마다 바꾼다.
     *
     * <p>{@code topK}는 {@link #CANDIDATES}(20)로 묻는다 — MRR@10을 내려면 일지 10개 이상이 필요하다.
     * 운영(topK 5)과 달리 벡터 후보가 80개가 되지만, 상위 순서는 거리순이라 같고 재순위는 어느 쪽이든 앞 20개만 본다.
     */
    private List<Ranked> service(String method, String query, Map<Long, Long> translationToLog) {
        boolean[] flags = SVC_METHODS.get(method);
        Object target = org.springframework.test.util.AopTestUtils.getTargetObject(retrievalService);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "hybridEnabled", flags[0]);
        org.springframework.test.util.ReflectionTestUtils.setField(target, "rerankEnabled", flags[1]);
        try {
            List<Ranked> chunks = new ArrayList<>();
            for (var c : retrievalService.search(query, "eval", true, CANDIDATES)) {
                chunks.add(toRanked(c.sourceType().name(), c.sourceId(), c.score(), translationToLog));
            }
            return byDocument(chunks);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(target, "hybridEnabled", false);
            org.springframework.test.util.ReflectionTestUtils.setField(target, "rerankEnabled", false);
        }
    }

    private List<Ranked> vector(Connection conn, String method, String literal,
                                Map<Long, Long> translationToLog) throws SQLException {
        boolean wasAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            // SET LOCAL — 이 트랜잭션에만. 커넥션 풀로 돌아가는 커넥션에 설정을 남기지 않는다.
            switch (method) {
                case "ann-default" -> {
                    st.execute("SET LOCAL hnsw.ef_search = 40");
                    st.execute("SET LOCAL hnsw.iterative_scan = off");
                }
                case "ann-decided", "ann-no-translation" -> {
                    st.execute("SET LOCAL hnsw.ef_search = 100");
                    st.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
                }
                case "exact" -> st.execute("SET LOCAL enable_indexscan = off");
                default -> throw new IllegalArgumentException(method);
            }
            String filter = method.equals("ann-no-translation") ? "  AND source_type <> 'WORK_LOG_TRANSLATION'\n" : "";
            String sql = """
                    SELECT source_type, source_id, embedding <=> CAST(? AS vector) AS distance
                    FROM document_chunks
                    WHERE embedding_model = ?
                    """ + filter + """
                    ORDER BY embedding <=> CAST(? AS vector)
                    LIMIT ?
                    """;
            List<Ranked> chunks = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, literal);
                ps.setString(2, MODEL);
                ps.setString(3, literal);
                ps.setInt(4, CANDIDATES);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        chunks.add(toRanked(rs.getString(1), rs.getLong(2), 1.0 - rs.getDouble(3), translationToLog));
                    }
                }
            }
            conn.commit();
            return byDocument(chunks);
        } finally {
            conn.setAutoCommit(wasAuto);
        }
    }

    /** {@code RetrievalService.keywordFallback}과 같은 토큰화·같은 쿼리 꼴(관리자 경로). 점수가 없다. */
    private List<Ranked> keyword(Connection conn, String query, Map<Long, Long> translationToLog) throws SQLException {
        List<String> keywords = Arrays.stream(query.split("\\s+"))
                .map(t -> t.replaceAll("[^\\p{L}\\p{N}]", ""))
                .filter(t -> t.length() >= 2)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(3)
                .toList();
        Map<Long, Ranked> merged = new LinkedHashMap<>();
        for (String kw : keywords) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT chunk_id, source_type, source_id FROM document_chunks
                    WHERE embedding_model = ? AND content LIKE ?
                    ORDER BY chunk_id DESC LIMIT ?
                    """)) {
                ps.setString(1, MODEL);
                ps.setString(2, "%" + kw + "%");
                ps.setInt(3, TOP_K);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        merged.putIfAbsent(rs.getLong(1),
                                toRanked(rs.getString(2), rs.getLong(3), 0.0, translationToLog));
                    }
                }
            }
            if (merged.size() >= TOP_K) {
                break;
            }
        }
        return byDocument(new ArrayList<>(merged.values()).subList(0, Math.min(TOP_K, merged.size())));
    }

    /**
     * #186 이후의 키워드 폴백. {@code KeywordChunkJdbcRepository}와 같은 SQL 꼴(관리자 경로):
     * 키워드 최대 8개(중복 제거)를 한 쿼리에 넣고 맞은 개수 내림차순, 같으면 최신순.
     */
    private List<Ranked> keywordRanked(Connection conn, String query, Map<Long, Long> translationToLog) throws SQLException {
        List<String> keywords = Arrays.stream(query.split("\\s+"))
                .map(t -> t.replaceAll("[^\\p{L}\\p{N}]", ""))
                .filter(t -> t.length() >= 2)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(8)
                .toList();
        if (keywords.isEmpty()) {
            return List.of();
        }
        String matched = keywords.stream().map(k -> "CASE WHEN content LIKE ? THEN 1 ELSE 0 END")
                .collect(Collectors.joining(" + "));
        String sql = "SELECT chunk_id, source_type, source_id FROM (SELECT chunk_id, source_type, source_id, "
                + matched + " AS matched FROM document_chunks WHERE embedding_model = ?) c "
                + "WHERE matched > 0 ORDER BY matched DESC, chunk_id DESC LIMIT ?";
        List<Ranked> chunks = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (String kw : keywords) {
                ps.setString(i++, "%" + kw + "%");
            }
            ps.setString(i++, MODEL);
            ps.setInt(i, TOP_K);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    chunks.add(toRanked(rs.getString(2), rs.getLong(3), 0.0, translationToLog));
                }
            }
        }
        return byDocument(chunks);
    }

    private Ranked toRanked(String sourceType, long sourceId, double sim, Map<Long, Long> translationToLog) {
        return switch (sourceType) {
            case "WORK_LOG" -> new Ranked("log:" + sourceId, sourceId, sim);
            case "WORK_LOG_TRANSLATION" -> {
                Long log = translationToLog.get(sourceId);
                yield new Ranked("log:" + log, log, sim);
            }
            default -> new Ranked(sourceType + ":" + sourceId, null, sim);
        };
    }

    /** 같은 일지(원문·번역본·여러 청크)는 처음 나온 순위 하나로 친다. */
    private List<Ranked> byDocument(List<Ranked> chunks) {
        Map<String, Ranked> first = new LinkedHashMap<>();
        for (Ranked r : chunks) {
            first.putIfAbsent(r.key, r);
        }
        return new ArrayList<>(first.values());
    }

    // ------------------------------------------------------------------
    // 지표
    // ------------------------------------------------------------------

    /** {@code build}는 몇 번째 HNSW 인덱스로 물었는지. 인덱스와 무관한 방식은 1이다. */
    private record Result(Query q, String method, List<Ranked> ranked, Set<Long> relevant, int build) {

        boolean isRelevant(Ranked r) {
            return r.logId() != null && relevant.contains(r.logId());
        }

        double hit5() {
            return ranked.stream().limit(5).anyMatch(this::isRelevant) ? 1 : 0;
        }

        double rr10() {
            for (int i = 0; i < Math.min(10, ranked.size()); i++) {
                if (isRelevant(ranked.get(i))) {
                    return 1.0 / (i + 1);
                }
            }
            return 0;
        }

        double p5() {
            return ranked.stream().limit(5).filter(this::isRelevant).count() / 5.0;
        }

        double top1() {
            return ranked.isEmpty() ? 0 : ranked.get(0).similarity();
        }

        double gap12() {
            return ranked.size() < 2 ? top1() : ranked.get(0).similarity() - ranked.get(1).similarity();
        }

        double relative() {
            List<Ranked> top = ranked.subList(0, Math.min(10, ranked.size()));
            double mean = top.stream().mapToDouble(Ranked::similarity).average().orElse(0);
            return top1() - mean;
        }
    }

    private void writePerQuery(List<Result> results) throws IOException {
        StringBuilder sb = new StringBuilder("qid\ttype\tlang\tmethod\trank\tdoc\tsimilarity\trelevant\tn_relevant\tbuild\n");
        for (Result r : results) {
            for (int i = 0; i < Math.min(10, r.ranked.size()); i++) {
                Ranked x = r.ranked.get(i);
                sb.append(r.q.qid).append('\t').append(r.q.type).append('\t').append(r.q.lang).append('\t')
                        .append(r.method).append('\t').append(i + 1).append('\t').append(x.key).append('\t')
                        .append(String.format(Locale.ROOT, "%.4f", x.similarity)).append('\t')
                        .append(r.isRelevant(x) ? 1 : 0).append('\t').append(r.relevant.size())
                        .append('\t').append(r.build).append('\n');
            }
        }
        Files.writeString(OUT.resolve("per_query.tsv"), sb.toString(), StandardCharsets.UTF_8);
    }

    private String summarize(Conditions cond, List<Query> queries, Map<String, Set<Long>> labels,
                             Map<String, String> nExcluded, List<Result> results) {
        StringBuilder sb = new StringBuilder("# 검색 품질 측정 결과 (#170)\n\n## 조건\n\n").append(cond.text).append('\n');

        // 정답 수
        sb.append("## 정답 수 (질문별, 한국어 원문 기준)\n\n| qid | 유형 | 정답 일지 수 |\n|---|---|---|\n");
        Map<String, String> typeOf = new HashMap<>();
        queries.forEach(q -> typeOf.put(q.qid, q.type));
        List<String> zero = new ArrayList<>();
        labels.forEach((qid, set) -> {
            if (!typeOf.get(qid).equals("N")) {
                sb.append("| ").append(qid).append(" | ").append(typeOf.get(qid)).append(" | ").append(set.size()).append(" |\n");
                if (set.isEmpty()) {
                    zero.add(qid);
                }
            }
        });
        sb.append("\n정답 0건이라 집계에서 뺀 질문: ").append(zero.isEmpty() ? "없음" : zero).append('\n');
        sb.append("\nN에서 뺀 질문(핵심 단어가 원문에 있음): ").append(nExcluded.isEmpty() ? "없음" : nExcluded).append("\n\n");

        Predicate<Result> answerable = r -> !r.q.noAnswer && !r.relevant.isEmpty();

        // 방식 × 언어 × 유형
        sb.append("## 방식 × 언어 × 유형 — Hit@5 [95% 구간] / MRR@10 / P@5\n\n");
        sb.append("| 방식 | 언어 | 유형 | n | Hit@5 | MRR@10 | P@5 |\n|---|---|---|---|---|---|---|\n");
        for (String m : METHODS) {
            for (String lang : List.of("ko", "en", "vi")) {
                for (String type : List.of("T", "S", "전체")) {
                    List<Result> cell = results.stream().filter(answerable)
                            .filter(r -> r.method.equals(m) && r.q.lang.equals(lang)
                                    && (type.equals("전체") || r.q.type.equals(type)))
                            .toList();
                    if (cell.isEmpty()) {
                        continue;
                    }
                    double[] hit = cell.stream().mapToDouble(Result::hit5).toArray();
                    double[] ci = bootstrap(hit);
                    sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %.2f [%.2f, %.2f] | %.2f | %.2f |%n",
                            m, lang, type, cell.size(), mean(hit), ci[0], ci[1],
                            cell.stream().mapToDouble(Result::rr10).average().orElse(0),
                            cell.stream().mapToDouble(Result::p5).average().orElse(0)));
                }
            }
        }

        // 짝지은 비교
        sb.append("\n## 짝지은 비교 — 같은 질문에서 Hit@5 차이(B − A) [95% 구간]\n\n");
        sb.append("| 비교 | 언어 | n | A | B | 차이 [구간] |\n|---|---|---|---|---|---|\n");
        String[][] pairs = {
                {"ann-default", "ann-decided", "#180 운영값 → 결정값"},
                {"exact", "ann-decided", "E3 정확 → ANN(결정값)"},
                {"ann-no-translation", "ann-decided", "E2 번역본 없음 → 있음"},
                {"ann-decided", "keyword", "E4 벡터 → 키워드 폴백"},
                {"keyword", "keyword-ranked", "#186 키워드 최신순 → 맞은 개수순"},
                {"ann-decided", "svc-vector", "경로 검증: 하네스 SQL → 운영 코드 (같아야 한다)"},
                {"svc-vector", "svc-hybrid", "#210 벡터 → 하이브리드"},
                {"svc-vector", "svc-rerank", "#213 벡터 → 재순위"},
                {"svc-vector", "svc-hybrid-rerank", "벡터 → 하이브리드+재순위"},
                {"svc-hybrid", "svc-hybrid-rerank", "하이브리드 → +재순위"},
        };
        for (String[] p : pairs) {
            for (String lang : List.of("ko", "en", "vi")) {
                Map<String, Double> a = byQid(results, p[0], lang, answerable);
                Map<String, Double> b = byQid(results, p[1], lang, answerable);
                List<String> common = a.keySet().stream().filter(b::containsKey).sorted().toList();
                if (common.isEmpty()) {
                    continue;
                }
                double[] diff = common.stream().mapToDouble(q -> b.get(q) - a.get(q)).toArray();
                double[] ci = bootstrap(diff);
                sb.append(String.format(Locale.ROOT, "| %s | %s | %d | %.2f | %.2f | %+.2f [%+.2f, %+.2f] |%n",
                        p[2], lang, common.size(),
                        common.stream().mapToDouble(a::get).average().orElse(0),
                        common.stream().mapToDouble(b::get).average().orElse(0),
                        mean(diff), ci[0], ci[1]));
            }
        }

        // 지연
        sb.append("\n## 방식별 지연 — 검색 한 번(ms)\n\n");
        sb.append("`svc-*`는 질의 임베딩·재순위 호출을 포함한 운영 경로 전체다. 하네스 SQL 방식은 질의 벡터를 미리 만들어 SQL만 잰다 — 둘을 직접 비교하지 않는다.\n\n");
        sb.append("| 방식 | n | p50 | p95 | 최대 |\n|---|---|---|---|---|\n");
        latencyMs.forEach((m, xs) -> {
            double[] v = xs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            sb.append(String.format(Locale.ROOT, "| %s | %d | %.1f | %.1f | %.1f |%n",
                    m, v.length, v[(int) Math.floor(0.50 * (v.length - 1))],
                    v[(int) Math.floor(0.95 * (v.length - 1))], v[v.length - 1]));
        });

        // 답 없는 질문
        sb.append("\n## 답 없는 질문 — 근거 없음을 가를 수 있는가 (ann-decided)\n\n");
        sb.append("답 있는 질문의 95%를 살리는 문턱(그 신호의 5백분위)에서 답 없는 질문을 몇 % 거르는가.\n\n");
        sb.append("| 신호 | 언어 | 답 있음 n | 답 없음 n | AUROC | 95% 살릴 때 거르는 비율 |\n|---|---|---|---|---|---|\n");
        Map<String, java.util.function.ToDoubleFunction<Result>> signals = new LinkedHashMap<>();
        signals.put("top-1 유사도", Result::top1);
        signals.put("1·2위 격차", Result::gap12);
        signals.put("상대 점수(top1 − top10 평균)", Result::relative);
        for (var s : signals.entrySet()) {
            for (String lang : List.of("ko", "en", "vi", "전체")) {
                Predicate<Result> inLang = r -> r.method.equals("ann-decided")
                        && (lang.equals("전체") || r.q.lang.equals(lang));
                double[] pos = results.stream().filter(inLang).filter(answerable).mapToDouble(s.getValue()).toArray();
                double[] neg = results.stream().filter(inLang).filter(r -> r.q.noAnswer).mapToDouble(s.getValue()).toArray();
                if (pos.length == 0 || neg.length == 0) {
                    continue;
                }
                double threshold = percentile(pos, 0.05);
                double rejected = Arrays.stream(neg).filter(v -> v < threshold).count() / (double) neg.length;
                sb.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %.2f | %.0f%% |%n",
                        s.getKey(), lang, pos.length, neg.length, auroc(pos, neg), rejected * 100));
            }
        }
        sb.append("\n질문별 원 결과: `build/search-eval/per_query.tsv`\n");
        return sb.toString();
    }

    /**
     * 재구축 N회의 분포(#187). 같은 질문·같은 질의 벡터로 인덱스만 바꿔 물은 결과다.
     * 설정 비교(#180)는 <b>같은 인덱스 안에서</b> 짝지어 빼고, 그 차이가 재구축마다 어떻게 퍼지는지 본다.
     */
    private String rebuildSection(List<Result> results, int builds, List<Long> reindexMs) {
        Predicate<Result> answerable = r -> !r.q.noAnswer && !r.relevant.isEmpty();
        StringBuilder sb = new StringBuilder();
        sb.append("\n## HNSW 재구축 ").append(builds).append("회 — 인덱스만 바꿨을 때 Hit@5 (#187)\n\n");
        sb.append("매번 `REINDEX`(직렬, maintenance_work_mem 256MB). 질의 벡터는 같다. 본문 표는 1회차 인덱스 기준이다. ")
                .append("재구축 소요(초): ")
                .append(reindexMs.stream().map(ms -> String.valueOf(ms / 1000)).collect(Collectors.joining(", ")))
                .append("\n\n");
        sb.append("| 방식 | 언어 | n | 회차별 Hit@5 | 평균 | 최소 ~ 최대 |\n|---|---|---|---|---|---|\n");
        for (String m : ANN_METHODS) {
            for (String lang : List.of("ko", "en", "vi")) {
                double[] per = new double[builds];
                int n = 0;
                for (int b = 1; b <= builds; b++) {
                    int bb = b;
                    double[] hit = results.stream().filter(answerable)
                            .filter(r -> r.build == bb && r.method.equals(m) && r.q.lang.equals(lang))
                            .mapToDouble(Result::hit5).toArray();
                    per[b - 1] = mean(hit);
                    n = hit.length;
                }
                sb.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %.3f | %.2f ~ %.2f |%n",
                        m, lang, n, joined(per), mean(per),
                        Arrays.stream(per).min().orElse(0), Arrays.stream(per).max().orElse(0)));
            }
        }
        for (String lang : List.of("ko", "en", "vi")) {
            double exact = mean(results.stream().filter(answerable)
                    .filter(r -> r.method.equals("exact") && r.q.lang.equals(lang)).mapToDouble(Result::hit5).toArray());
            sb.append(String.format(Locale.ROOT, "| exact (기준, 인덱스 무관) | %s | | | %.3f | |%n", lang, exact));
        }

        sb.append("\n### #180 운영값 → 결정값, 같은 인덱스 안에서 짝지은 Hit@5 차이\n\n");
        sb.append("| 언어 | 회차별 차이 | 평균 | 최소 ~ 최대 | 차이 > 0인 회차 |\n|---|---|---|---|---|\n");
        for (String lang : List.of("ko", "en", "vi")) {
            double[] per = new double[builds];
            for (int b = 1; b <= builds; b++) {
                int bb = b;
                List<Result> inBuild = results.stream().filter(r -> r.build == bb).toList();
                Map<String, Double> a = byQid(inBuild, "ann-default", lang, answerable);
                Map<String, Double> d = byQid(inBuild, "ann-decided", lang, answerable);
                per[b - 1] = a.keySet().stream().filter(d::containsKey)
                        .mapToDouble(q -> d.get(q) - a.get(q)).average().orElse(0);
            }
            sb.append(String.format(Locale.ROOT, "| %s | %s | %+.3f | %+.2f ~ %+.2f | %d / %d |%n",
                    lang, joinedSigned(per), mean(per),
                    Arrays.stream(per).min().orElse(0), Arrays.stream(per).max().orElse(0),
                    Arrays.stream(per).filter(v -> v > 1e-9).count(), builds));
        }
        return sb.toString();
    }

    private static String joined(double[] v) {
        return Arrays.stream(v).mapToObj(x -> String.format(Locale.ROOT, "%.2f", x)).collect(Collectors.joining(" "));
    }

    private static String joinedSigned(double[] v) {
        return Arrays.stream(v).mapToObj(x -> String.format(Locale.ROOT, "%+.2f", x)).collect(Collectors.joining(" "));
    }

    private Map<String, Double> byQid(List<Result> results, String method, String lang, Predicate<Result> filter) {
        return results.stream().filter(filter).filter(r -> r.method.equals(method) && r.q.lang.equals(lang))
                .collect(Collectors.toMap(r -> r.q.qid, Result::hit5, (x, y) -> x, LinkedHashMap::new));
    }

    private static double mean(double[] v) {
        return Arrays.stream(v).average().orElse(0);
    }

    /** 질문을 복원 추출해 평균의 95% 구간. 시드 고정 — 다시 돌려도 같은 구간. */
    private static double[] bootstrap(double[] v) {
        if (v.length == 0) {
            return new double[]{0, 0};
        }
        Random random = new Random(170L);
        double[] means = new double[BOOTSTRAP];
        for (int b = 0; b < BOOTSTRAP; b++) {
            double sum = 0;
            for (int i = 0; i < v.length; i++) {
                sum += v[random.nextInt(v.length)];
            }
            means[b] = sum / v.length;
        }
        Arrays.sort(means);
        return new double[]{means[(int) (BOOTSTRAP * 0.025)], means[(int) (BOOTSTRAP * 0.975) - 1]};
    }

    /** P(답 있음의 신호 > 답 없음의 신호). 동점은 절반. */
    private static double auroc(double[] pos, double[] neg) {
        double wins = 0;
        for (double p : pos) {
            for (double n : neg) {
                wins += p > n ? 1 : p == n ? 0.5 : 0;
            }
        }
        return wins / (pos.length * (double) neg.length);
    }

    private static double percentile(double[] v, double q) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s[(int) Math.floor(q * (s.length - 1))];
    }
}
