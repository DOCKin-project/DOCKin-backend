package com.DOCKin.rag.service;

import com.DOCKin.ai.dto.ChatDomain;
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

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 끝에서 끝까지 — 답변 평가 하네스의 수집 단계. ADR-0013 D5.
 *
 * <h3>무엇을 하나</h3>
 * <ol>
 *   <li>{@link SearchQualityEvalTest}와 같은 코퍼스를 만든다(캐시가 있으면 불러온다). 정답 일지도 같은 SQL로 계산한다</li>
 *   <li>{@code queries.tsv}의 질문마다 <b>실제 {@link RagChatService}</b>를 부른다 — 검색, 프롬프트 조립, 챗봇 서버 호출까지 운영과 같은 경로</li>
 *   <li>답, 근거 청크 본문, 근거가 정답 일지를 포함했는지, 걸린 시간을 {@code answers.jsonl}로 남긴다</li>
 *   <li>후속 질문(#198)은 LLM 없이 검색만 잰다 — 앞 질문을 붙였을 때와 안 붙였을 때 Hit@5</li>
 * </ol>
 * 채점(답변 충실도·출처·답 없음 처리·언어 일치)은 {@code measure/rag-e2e/judge.py}가 따로 한다.
 * 수집과 채점을 나눈 이유: 판정 모델을 바꿔 다시 채점할 때 답을 다시 받지 않는다.
 *
 * <h3>경로 — 관리자(권한 필터 없음)</h3>
 * 검색 평가와 같게 관리자 경로로 돈다. 권한 누출은 이 하네스가 아니라 선필터 테스트가 본다.
 *
 * <h3>실행</h3>
 * <pre>
 * # TEI(임베딩)와 pylab aiserver(챗봇, /api/chatbot)가 떠 있어야 한다
 * RUN_RAG_EVAL=1 SEARCH_EVAL_EMBED_URL=http://localhost:58081 SEARCH_EVAL_CORPUS=20000 \
 *   TEST_AI_SERVER_URL=http://localhost:8000 RAG_EVAL_TAG=qwen2.5-7b \
 *   ./gradlew test --tests "*RagAnswerEvalTest"
 * # 하네스만 확인할 때: RAG_EVAL_STUB=1 (모델 없이 근거 목록을 답 자리에)
 * </pre>
 * 산출물은 {@code build/rag-e2e/<TAG>/}.
 */
@SpringBootTest(properties = {
        "rag.indexing.cron=-"
})
@DisplayName("RAG 답변 평가 수집 (ADR-0013 D5) - RUN_RAG_EVAL이 있을 때만")
class RagAnswerEvalTest extends ContainerTestSupport {

    private static final int TOP_K = 5;

    /** 후속 질문. 앞 질문 없이는 무엇을 묻는지 알 수 없는 꼴로 둔다(#198). */
    private static final Map<String, String> FOLLOW_UP = Map.of(
            "ko", "그거 원인이 뭐였어?",
            "en", "What was the cause of that?",
            "vi", "Nguyên nhân của việc đó là gì?");

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("external-api.embedding.base-url", SearchQualityEvalTest::embedUrl);
        registry.add("ai.chatbot.stub", () -> System.getenv("RAG_EVAL_STUB") != null ? "true" : "false");
    }

    private static String aiUrl() {
        String v = System.getenv("TEST_AI_SERVER_URL");   // ContainerTestSupport가 이 값으로 챗봇 서버 주소를 건다
        return v == null || v.isBlank() ? "http://localhost:8000" : v.trim();
    }

    private static String tag() {
        String v = System.getenv("RAG_EVAL_TAG");
        if (System.getenv("RAG_EVAL_STUB") != null) {
            return "stub";
        }
        return v == null || v.isBlank() ? "unnamed" : v.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Autowired private DataSource dataSource;
    @Autowired private IndexingService indexingService;
    @Autowired private RagChatService ragChatService;
    @Autowired private RetrievalService retrievalService;

    /** 코퍼스·정답 계산은 검색 평가와 한 벌이다. 그쪽 메서드는 주입된 빈을 쓰지 않는다. */
    private final SearchQualityEvalTest corpus = new SearchQualityEvalTest();

    @Test
    void 답변_수집() throws Exception {
        Assumptions.assumeTrue(System.getenv("RUN_RAG_EVAL") != null,
                "RUN_RAG_EVAL이 없어 건너뜁니다. 코퍼스 색인과 LLM 호출이 필요한 측정입니다.");
        Assumptions.assumeTrue(corpus.embeddingUp(), "임베딩 서버(" + SearchQualityEvalTest.embedUrl() + ")가 없어 건너뜁니다.");
        int size = Integer.parseInt(System.getenv().getOrDefault("SEARCH_EVAL_CORPUS", "20000").trim());
        Path out = Path.of("build/rag-e2e", tag());
        Files.createDirectories(out);

        prepareCorpus(size);

        try (Connection conn = dataSource.getConnection()) {
            String userId = anyUser(conn);
            Map<Long, Long> translationToLog = corpus.translationToLog(conn);
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

            // 1. 답변 — 운영과 같은 경로
            int n = 0;
            int errors = 0;
            try (BufferedWriter w = Files.newBufferedWriter(out.resolve("answers.jsonl"), StandardCharsets.UTF_8)) {
                for (SearchQualityEvalTest.Query q : queries) {
                    if (q.noAnswer() && nExcluded.containsKey(q.qid())) {
                        continue;
                    }
                    ChatDomain.Request request = new ChatDomain.Request(
                            List.of(new ChatDomain.Request.Message("user", q.query())),
                            q.lang(), "rag-eval-" + q.qid() + "-" + q.lang());
                    long t0 = System.nanoTime();
                    ChatDomain.Response response = null;
                    String error = null;
                    try {
                        response = ragChatService.chat(request, userId, true);
                    } catch (RuntimeException e) {
                        error = e.getClass().getSimpleName() + ": " + e.getMessage();
                        errors++;
                    }
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    w.write(answerJson(conn, q, labels.get(q.qid()), response, error, ms, translationToLog));
                    w.newLine();
                    w.flush();
                    n++;
                    System.out.printf("[rag-eval] %s %s %s %dms%s%n", q.qid(), q.lang(), q.type(), ms,
                            error == null ? "" : " ERROR " + error);
                }
            }

            // 2. 후속 질문 — 검색만, LLM 없이(#198)
            String followUps = followUps(queries, labels, userId, translationToLog, out);

            String summary = "# RAG 답변 수집 (" + tag() + ")\n\n"
                    + "| 항목 | 값 |\n|---|---|\n"
                    + "| 실행 | " + LocalDateTime.now().withNano(0) + " |\n"
                    + "| 챗봇 서버 | " + (System.getenv("RAG_EVAL_STUB") != null ? "스텁(모델 없음)" : aiUrl()) + " |\n"
                    + "| 코퍼스 | " + String.format("%,d", size) + " |\n"
                    + "| 질문 | " + n + " (오류 " + errors + ") |\n"
                    + "| N에서 뺀 질문 | " + (nExcluded.isEmpty() ? "없음" : nExcluded) + " |\n\n"
                    + followUps;
            Files.writeString(out.resolve("collect.md"), summary, StandardCharsets.UTF_8);
            System.out.println(summary);
        }
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
            if (!rs.next()) {
                throw new IllegalStateException("코퍼스에 사용자가 없습니다");
            }
            return rs.getString(1);
        }
    }

    // ------------------------------------------------------------------

    private String answerJson(Connection conn, SearchQualityEvalTest.Query q, Set<Long> relevant,
                              ChatDomain.Response response, String error, long ms,
                              Map<Long, Long> translationToLog) throws Exception {
        List<ChatDomain.Response.Source> sources = response == null || response.retrieval() == null
                ? List.of() : response.retrieval().sources();
        Map<Long, String> contents = contents(conn, sources.stream().map(ChatDomain.Response.Source::chunkId).toList());

        StringBuilder sb = new StringBuilder("{");
        field(sb, "qid", q.qid()).append(',');
        field(sb, "type", q.type()).append(',');
        field(sb, "lang", q.lang()).append(',');
        field(sb, "query", q.query()).append(',');
        sb.append("\"no_answer\":").append(q.noAnswer()).append(',');
        sb.append("\"relevant_count\":").append(relevant.size()).append(',');
        field(sb, "mode", response == null || response.retrieval() == null ? null : response.retrieval().mode()).append(',');
        sb.append("\"sources\":[");
        boolean hit = false;
        for (int i = 0; i < sources.size(); i++) {
            ChatDomain.Response.Source s = sources.get(i);
            Long logId = "WORK_LOG".equals(s.sourceType()) ? Long.valueOf(s.sourceId())
                    : "WORK_LOG_TRANSLATION".equals(s.sourceType()) ? translationToLog.get(s.sourceId()) : null;
            boolean rel = logId != null && relevant.contains(logId);
            hit |= rel;
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{').append("\"n\":").append(i + 1).append(',');
            sb.append("\"chunk_id\":").append(s.chunkId()).append(',');
            field(sb, "source_type", s.sourceType()).append(',');
            sb.append("\"source_id\":").append(s.sourceId()).append(',');
            sb.append("\"log_id\":").append(logId).append(',');
            sb.append("\"relevant\":").append(rel).append(',');
            sb.append("\"score\":").append(String.format(java.util.Locale.ROOT, "%.4f", s.score())).append(',');
            field(sb, "content", contents.get(s.chunkId()));
            sb.append('}');
        }
        sb.append("],");
        sb.append("\"hit\":").append(hit).append(',');
        field(sb, "reply", response == null ? null : response.result().reply()).append(',');
        field(sb, "error", error).append(',');
        sb.append("\"latency_ms\":").append(ms);
        return sb.append('}').toString();
    }

    private static Map<Long, String> contents(Connection conn, List<Long> chunkIds) throws Exception {
        Map<Long, String> map = new HashMap<>();
        if (chunkIds.isEmpty()) {
            return map;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT chunk_id, content FROM document_chunks WHERE chunk_id = ANY(?)")) {
            Array arr = conn.createArrayOf("bigint", chunkIds.toArray());
            ps.setArray(1, arr);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getLong(1), rs.getString(2));
                }
            }
        }
        return map;
    }

    /** 후속 질문 Hit@5 — 앞 질문 없이 vs 붙여서. 답 있는 질문만, 언어별. */
    private String followUps(List<SearchQualityEvalTest.Query> queries, Map<String, Set<Long>> labels,
                             String userId, Map<Long, Long> translationToLog, Path out) throws Exception {
        Map<String, int[]> byLang = new LinkedHashMap<>();   // lang -> {n, bareHits, contextHits}
        List<String> rows = new ArrayList<>();
        rows.add("qid\ttype\tlang\tfollow_up\tbare_hit\tcontext_hit");
        for (SearchQualityEvalTest.Query q : queries) {
            Set<Long> relevant = labels.get(q.qid());
            if (q.noAnswer() || relevant == null || relevant.isEmpty()) {
                continue;
            }
            String followUp = FOLLOW_UP.get(q.lang());
            if (followUp == null) {
                continue;
            }
            ChatDomain.Request conversation = new ChatDomain.Request(List.of(
                    new ChatDomain.Request.Message("user", q.query()),
                    new ChatDomain.Request.Message("assistant", "-"),
                    new ChatDomain.Request.Message("user", followUp)), q.lang(), null);
            boolean bare = anyRelevant(retrievalService.retrieve(followUp, userId, true, TOP_K), relevant, translationToLog);
            boolean ctx = anyRelevant(retrievalService.retrieve(
                    RagChatService.searchQuery(conversation, followUp), userId, true, TOP_K), relevant, translationToLog);
            int[] c = byLang.computeIfAbsent(q.lang(), k -> new int[3]);
            c[0]++;
            c[1] += bare ? 1 : 0;
            c[2] += ctx ? 1 : 0;
            rows.add(q.qid() + "\t" + q.type() + "\t" + q.lang() + "\t" + followUp + "\t" + (bare ? 1 : 0) + "\t" + (ctx ? 1 : 0));
        }
        Files.write(out.resolve("followups.tsv"), rows, StandardCharsets.UTF_8);

        StringBuilder sb = new StringBuilder("## 후속 질문 Hit@5 — 검색만 (#198)\n\n")
                .append("| 언어 | n | 앞 질문 없이 | 앞 질문 붙여서 |\n|---|---|---|---|\n");
        byLang.forEach((lang, c) -> sb.append(String.format(java.util.Locale.ROOT, "| %s | %d | %.2f | %.2f |%n",
                lang, c[0], (double) c[1] / c[0], (double) c[2] / c[0])));
        return sb.toString();
    }

    private static boolean anyRelevant(RetrievalResult result, Set<Long> relevant, Map<Long, Long> translationToLog) {
        for (RetrievedChunk c : result.chunks()) {
            Long logId = switch (c.sourceType()) {
                case WORK_LOG -> c.sourceId();
                case WORK_LOG_TRANSLATION -> translationToLog.get(c.sourceId());
                default -> null;
            };
            if (logId != null && relevant.contains(logId)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // JSON — 의존성 없이. 값은 문자열과 숫자·불리언뿐이다.

    private static StringBuilder field(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            return sb.append("null");
        }
        sb.append('"');
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"');
    }
}
