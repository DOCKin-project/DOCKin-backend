package com.DOCKin.rag.service;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.rag.dto.RetrievalResult;
import com.DOCKin.rag.dto.RetrievedChunk;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * 검색 방식 비교 — 벡터 / 하이브리드(#210) / 재순위(#213) / 하이브리드+재순위. ADR-0013 2단계 3·4, #203.
 *
 * <h3>왜 따로 두나</h3>
 * {@link SearchQualityEvalTest}는 SQL을 직접 짜서 방식을 흉내 낸다. 하이브리드·재순위는 {@link RetrievalService} 안의
 * 순서(후보 → RRF → 재순위 → 문서당 상한)가 결과를 정하므로, 흉내 내지 않고 <b>운영 빈의 플래그만 바꿔</b> 그대로 부른다.
 * 그래서 지표도 운영과 같은 단위다 — 질문당 <b>근거 청크 5개</b>(LLM이 받는 것).
 * <ul>
 *   <li>Hit@5 — 5개 중 정답 일지가 하나라도 있으면 1</li>
 *   <li>P@5 — 5개 중 정답 일지 청크의 비율. 엉뚱한 근거가 섞이는 정도(#203)</li>
 *   <li>MRR@5 — 첫 정답 청크 순위의 역수</li>
 * </ul>
 * {@link SearchQualityEvalTest}의 표(일지 단위, MRR@10)와 숫자를 직접 비교하지 않는다. 같은 클래스 안의 {@code vector}와 비교한다.
 *
 * <h3>근거 없음 신호</h3>
 * 답 없는 질문(N)을 가르는 신호로 top-1 코사인과 top-1 재순위 점수를 언어별로 비교한다(AUROC, 답 있는 95%를 살리는 문턱).
 *
 * <h3>실행</h3>
 * <pre>
 * # TEI 임베딩(:58081)과 TEI 재순위(:8082, BAAI/bge-reranker-v2-m3)가 떠 있어야 한다
 * RUN_MODE_EVAL=1 SEARCH_EVAL_EMBED_URL=http://localhost:58081 RERANKER_SERVER_URL=http://localhost:8082 \
 *   SEARCH_EVAL_CORPUS=20000 ./gradlew test --tests "*RetrievalModeEvalTest"
 * </pre>
 * 산출물은 {@code build/search-eval-modes/}.
 */
@SpringBootTest(properties = {
        "rag.indexing.cron=-",
        // 재순위가 느려 타임아웃으로 떨어지면 조용히 재순위 전 순서로 답한다. 측정에서는 넉넉히 두고, 실패는 따로 센다.
        "external-api.reranker.response-timeout-ms=60000"
})
@DisplayName("검색 방식 비교 (#210, #213) - RUN_MODE_EVAL이 있을 때만")
class RetrievalModeEvalTest extends ContainerTestSupport {

    private static final int TOP_K = 5;
    private static final int BOOTSTRAP = 2000;
    /** 재순위 모델을 바꿔 가며 잴 때 결과가 섞이지 않게 {@code MODE_EVAL_TAG}(모델 이름 등)로 하위 폴더를 나눈다. */
    private static final Path OUT = Path.of("build/search-eval-modes",
            System.getenv().getOrDefault("MODE_EVAL_TAG", "default").replaceAll("[^A-Za-z0-9._-]", "_"));
    /** {@code MODE_EVAL_MODES=vector,rerank}처럼 고른다. 근거 없음 신호 표에는 {@code vector}가 있어야 코사인 줄이 나온다. */
    private static final List<String> MODES = List.of(System.getenv()
            .getOrDefault("MODE_EVAL_MODES", "vector,hybrid,rerank,hybrid+rerank").split(","));

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("external-api.embedding.base-url", SearchQualityEvalTest::embedUrl);
    }

    @Autowired private DataSource dataSource;
    @Autowired private IndexingService indexingService;
    @Autowired private RetrievalService retrievalService;
    @Autowired private RerankClient rerankClient;

    private final SearchQualityEvalTest corpus = new SearchQualityEvalTest();

    private record Row(SearchQualityEvalTest.Query q, String mode, List<Long> logIds, List<Boolean> rel,
                       double topCosine, double topRerank, long ms) {
        boolean hit() { return rel.contains(true); }
        double precision() { return rel.isEmpty() ? 0 : rel.stream().filter(b -> b).count() / (double) TOP_K; }
        double mrr() { int i = rel.indexOf(true); return i < 0 ? 0 : 1.0 / (i + 1); }
    }

    @Test
    void 방식_비교() throws Exception {
        Assumptions.assumeTrue(System.getenv("RUN_MODE_EVAL") != null, "RUN_MODE_EVAL이 없어 건너뜁니다.");
        Assumptions.assumeTrue(corpus.embeddingUp(), "임베딩 서버가 없어 건너뜁니다.");
        Optional<double[]> probe = rerankClient.score("용접기 송급 롤러", List.of("CO2 용접기 송급 롤러 교체", "지게차 타이어 점검"));
        Assumptions.assumeTrue(probe.isPresent() && probe.get().length == 2, "재순위 서버가 없어 건너뜁니다.");
        int size = Integer.parseInt(System.getenv().getOrDefault("SEARCH_EVAL_CORPUS", "20000").trim());
        Files.createDirectories(OUT);
        prepareCorpus(size);

        RetrievalService target = AopTestUtils.getUltimateTargetObject(retrievalService);
        try (Connection conn = dataSource.getConnection()) {
            String userId = anyUser(conn);
            Map<Long, Long> t2l = corpus.translationToLog(conn);
            List<SearchQualityEvalTest.Query> queries = corpus.loadQueries();
            Map<String, Set<Long>> labels = new LinkedHashMap<>();
            Map<String, String> nExcluded = new LinkedHashMap<>();
            for (SearchQualityEvalTest.Query q : queries) {
                if (!q.lang().equals("ko") || labels.containsKey(q.qid())) {
                    continue;
                }
                if (q.noAnswer()) {
                    String hit = corpus.presentTerm(conn,
                            SearchQualityEvalTest.N_ABSENT_TERMS.getOrDefault(q.qid(), List.of()));
                    if (hit != null) {
                        nExcluded.put(q.qid(), hit);
                    }
                    labels.put(q.qid(), Set.of());
                } else {
                    labels.put(q.qid(), corpus.relevant(conn, q));
                }
            }

            List<Row> rows = new ArrayList<>();
            int rerankFailures = 0;
            for (String mode : MODES) {
                ReflectionTestUtils.setField(target, "hybridEnabled", mode.contains("hybrid"));
                ReflectionTestUtils.setField(target, "rerankEnabled", mode.contains("rerank"));
                ReflectionTestUtils.setField(target, "rerankMinScore", 0.0);
                for (SearchQualityEvalTest.Query q : queries) {
                    if (q.noAnswer() && nExcluded.containsKey(q.qid())) {
                        continue;
                    }
                    Set<Long> relevant = labels.get(q.qid());
                    long t0 = System.nanoTime();
                    RetrievalResult r = retrievalService.retrieve(q.query(), userId, true, TOP_K);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    List<Long> logIds = new ArrayList<>();
                    List<Boolean> rel = new ArrayList<>();
                    for (RetrievedChunk c : r.chunks()) {
                        Long logId = switch (c.sourceType()) {
                            case WORK_LOG -> c.sourceId();
                            case WORK_LOG_TRANSLATION -> t2l.get(c.sourceId());
                            default -> null;
                        };
                        logIds.add(logId);
                        rel.add(logId != null && relevant.contains(logId));
                    }
                    double topCos = r.chunks().isEmpty() ? 0 : r.chunks().get(0).score();
                    double topRerank = Double.NaN;
                    if (mode.contains("rerank") && !r.chunks().isEmpty()) {
                        Optional<double[]> s = rerankClient.score(q.query(), List.of(r.chunks().get(0).content()));
                        if (s.isPresent() && s.get().length == 1) {
                            topRerank = s.get()[0];
                        } else {
                            rerankFailures++;
                        }
                    }
                    rows.add(new Row(q, mode, logIds, rel, topCos, topRerank, ms));
                }
                System.out.printf("[mode-eval] %s 끝 (%d행)%n", mode, rows.size());
            }
            ReflectionTestUtils.setField(target, "hybridEnabled", false);
            ReflectionTestUtils.setField(target, "rerankEnabled", false);

            writePerQuery(rows);
            String summary = summarize(size, rows, nExcluded, rerankFailures);
            Files.writeString(OUT.resolve("summary.md"), summary, StandardCharsets.UTF_8);
            System.out.println(summary);
        }
    }

    // ------------------------------------------------------------------

    private String summarize(int size, List<Row> rows, Map<String, String> nExcluded, int rerankFailures) {
        StringBuilder sb = new StringBuilder("# 검색 방식 비교 — 벡터 / 하이브리드 / 재순위\n\n")
                .append("| 항목 | 값 |\n|---|---|\n")
                .append("| 실행 | ").append(LocalDateTime.now().withNano(0)).append(" |\n")
                .append("| 코퍼스 | ").append(String.format("%,d", size)).append(" |\n")
                .append("| 경로 | 운영 `RetrievalService.retrieve`(관리자), topK ").append(TOP_K)
                .append(", 플래그만 바꿈. 하이브리드 rrf-k·재순위 후보 수는 운영 기본값 |\n")
                .append("| 재순위 점수 재호출 실패 | ").append(rerankFailures).append(" |\n")
                .append("| N에서 뺀 질문 | ").append(nExcluded.isEmpty() ? "없음" : nExcluded).append(" |\n\n");

        sb.append("## 1. 방식 × 언어 × 유형 — Hit@5 / P@5 / MRR@5 (답 있는 질문)\n\n")
                .append("| 방식 | 언어 | 유형 | n | Hit@5 | P@5 | MRR@5 | p50 ms | p95 ms |\n|---|---|---|---|---|---|---|---|---|\n");
        for (String mode : MODES) {
            for (String lang : List.of("ko", "en", "vi")) {
                for (String type : List.of("T", "S", "전체")) {
                    List<Row> s = rows.stream().filter(r -> r.mode.equals(mode) && r.q.lang().equals(lang)
                            && !r.q.noAnswer() && (type.equals("전체") || r.q.type().equals(type))).toList();
                    if (s.isEmpty()) {
                        continue;
                    }
                    long[] lat = s.stream().mapToLong(Row::ms).sorted().toArray();
                    sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %.2f | %.2f | %.2f | %d | %d |%n",
                            mode, lang, type, s.size(),
                            s.stream().mapToDouble(r -> r.hit() ? 1 : 0).average().orElse(0),
                            s.stream().mapToDouble(Row::precision).average().orElse(0),
                            s.stream().mapToDouble(Row::mrr).average().orElse(0),
                            lat[lat.length / 2], lat[Math.min(lat.length - 1, (int) (lat.length * 0.95))]));
                }
            }
        }

        sb.append("\n## 2. vector 대비 차이 — 같은 질문끼리 짝지은 부트스트랩 95% 구간\n\n")
                .append("SLO 7-1 A: 어느 칸이든 Hit@5 차이 구간의 위끝이 0보다 작으면(확실히 나빠지면) 막는다.\n\n")
                .append("| 방식 | 언어 | 유형 | n | ΔHit@5 [95%] | ΔP@5 [95%] |\n|---|---|---|---|---|---|\n");
        for (String mode : MODES.subList(1, MODES.size())) {
            for (String lang : List.of("ko", "en", "vi")) {
                for (String type : List.of("T", "S", "전체")) {
                    List<double[]> pairs = paired(rows, mode, lang, type);
                    if (pairs.isEmpty()) {
                        continue;
                    }
                    double[] dHit = pairs.stream().mapToDouble(p -> p[1] - p[0]).toArray();
                    double[] dP = pairs.stream().mapToDouble(p -> p[3] - p[2]).toArray();
                    sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %s | %s |%n",
                            mode, lang, type, pairs.size(), ci(dHit), ci(dP)));
                }
            }
        }

        sb.append("\n## 3. 근거 없음 신호 — 답 있음 vs 답 없음(N)\n\n")
                .append("문턱 = 답 있는 질문의 5백분위(95%를 살린다). 그 문턱에서 답 없는 질문을 몇 % 거르나.\n\n")
                .append("| 신호 | 언어 | 답 있음 n | 답 없음 n | AUROC | 문턱 | N 거름 |\n|---|---|---|---|---|---|---|\n");
        for (String[] sig : new String[][]{{"vector", "top-1 코사인"}, {"rerank", "top-1 재순위"},
                {"hybrid+rerank", "top-1 재순위(하이브리드 후보)"}}) {
            for (String lang : List.of("ko", "en", "vi", "전체")) {
                List<Row> s = rows.stream().filter(r -> r.mode.equals(sig[0])
                        && (lang.equals("전체") || r.q.lang().equals(lang))).toList();
                boolean rr = sig[0].contains("rerank");
                double[] pos = s.stream().filter(r -> !r.q.noAnswer()).mapToDouble(r -> rr ? r.topRerank : r.topCosine)
                        .filter(v -> !Double.isNaN(v)).toArray();
                double[] neg = s.stream().filter(r -> r.q.noAnswer()).mapToDouble(r -> rr ? r.topRerank : r.topCosine)
                        .filter(v -> !Double.isNaN(v)).toArray();
                if (pos.length == 0 || neg.length == 0) {
                    continue;
                }
                double th = percentile(pos, 0.05);
                long filtered = Arrays.stream(neg).filter(v -> v < th).count();
                sb.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %.2f | %.3f | %.0f%% |%n",
                        sig[1], lang, pos.length, neg.length, auroc(pos, neg), th, 100.0 * filtered / neg.length));
            }
        }
        sb.append("\n질문별 원 결과: `per_query.tsv`. 합성 코퍼스·Claude가 쓴 질문(사람 검수 없음)이라 방향만 읽는다.\n");
        return sb.toString();
    }

    /** [base hit, mode hit, base P, mode P] 질문 쌍. */
    private static List<double[]> paired(List<Row> rows, String mode, String lang, String type) {
        Map<String, Row> base = new LinkedHashMap<>();
        rows.stream().filter(r -> r.mode.equals("vector")).forEach(r -> base.put(r.q.qid() + r.q.lang(), r));
        List<double[]> out = new ArrayList<>();
        for (Row r : rows) {
            if (!r.mode.equals(mode) || !r.q.lang().equals(lang) || r.q.noAnswer()
                    || !(type.equals("전체") || r.q.type().equals(type))) {
                continue;
            }
            Row b = base.get(r.q.qid() + r.q.lang());
            out.add(new double[]{b.hit() ? 1 : 0, r.hit() ? 1 : 0, b.precision(), r.precision()});
        }
        return out;
    }

    private static String ci(double[] d) {
        double mean = Arrays.stream(d).average().orElse(0);
        Random rnd = new Random(13);
        double[] boot = new double[BOOTSTRAP];
        for (int i = 0; i < BOOTSTRAP; i++) {
            double s = 0;
            for (int j = 0; j < d.length; j++) {
                s += d[rnd.nextInt(d.length)];
            }
            boot[i] = s / d.length;
        }
        return String.format(Locale.ROOT, "%+.2f [%+.2f, %+.2f]", mean, percentile(boot, 0.025), percentile(boot, 0.975));
    }

    private static double auroc(double[] pos, double[] neg) {
        double wins = 0;
        for (double p : pos) {
            for (double n : neg) {
                wins += p > n ? 1 : p == n ? 0.5 : 0;
            }
        }
        return wins / ((double) pos.length * neg.length);
    }

    private static double percentile(double[] v, double q) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s[Math.min(s.length - 1, Math.max(0, (int) Math.floor(q * (s.length - 1))))];
    }

    private void writePerQuery(List<Row> rows) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("qid\ttype\tlang\tno_answer\tmode\thit\tp5\tmrr5\ttop_cosine\ttop_rerank\tms\tlog_ids");
        for (Row r : rows) {
            lines.add(String.join("\t", r.q.qid(), r.q.type(), r.q.lang(), String.valueOf(r.q.noAnswer()), r.mode,
                    r.hit() ? "1" : "0", String.format(Locale.ROOT, "%.2f", r.precision()),
                    String.format(Locale.ROOT, "%.3f", r.mrr()), String.format(Locale.ROOT, "%.4f", r.topCosine),
                    Double.isNaN(r.topRerank) ? "" : String.format(Locale.ROOT, "%.4f", r.topRerank),
                    String.valueOf(r.ms), r.logIds.toString()));
        }
        Files.write(OUT.resolve("per_query.tsv"), lines, StandardCharsets.UTF_8);
    }

    private void prepareCorpus(int size) throws Exception {
        Path cache = SearchQualityEvalTest.cacheDir().resolve("corpus-" + size);
        if (Files.isDirectory(cache)) {
            try (Connection conn = dataSource.getConnection()) {
                corpus.restore(conn, cache);
            }
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            corpus.seedEquipment(conn);
            new CorpusSeedGeneratorTest().seed(conn, size);
        }
        IndexingService.IndexRun run = indexingService.indexAll();
        if (!run.completed()) {
            throw new IllegalStateException("색인이 끝나지 않았습니다: " + run.abortReason());
        }
        try (Connection conn = dataSource.getConnection()) {
            corpus.dump(conn, cache);
        }
    }

    private static String anyUser(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT user_id FROM users ORDER BY user_id LIMIT 1")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
