package com.DOCKin.rag.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 교차언어 검색 검증: <b>베트남어·영어로 물어도 한국어 원문이 검색되는가?</b>
 *
 * <h3>왜 이 테스트가 필요한가</h3>
 * ADR-0003 3-3은 교차언어 검색을 위해 Elasticsearch에 언어별 analyzer(한국어 Nori, 그 외 ICU)를
 * 구성하는 방안을 계획했었다. 다국어 임베딩 모델을 쓰면 <b>번역본을 같은 벡터 공간에 넣는 것만으로</b>
 * 그 구성 없이 교차언어가 성립한다는 것이 이 결정의 전제였다. 전제를 실측으로 확인한다.
 *
 * <p>실제 임베딩 서버(TEI, {@code multilingual-e5-small})를 호출한다.
 * 서버가 없으면 실패가 아니라 <b>skip</b> 된다.
 *
 * <h3>한계</h3>
 * 표본이 5쌍이다. "모델이 교차언어를 지원한다"는 방향 확인이지 정확도 수치가 아니다.
 * 실제 recall@k는 {@code work_log_translations}에 데이터가 쌓인 뒤
 * {@code (log_id, language_code)}를 정답 라벨로 삼아 측정해야 한다.
 */
class CrossLingualRetrievalTest {

    private static final String EMBED_URL = "http://localhost:8081/embed";

    /** 한국어 원문 코퍼스. 실제로는 work_logs / work_log_translations에서 색인된다. */
    private static final String[] KOREAN = {
            "용접 작업을 할 때는 반드시 보호구를 착용해야 한다.",
            "크레인 하부로 통행하는 것을 금지한다.",
            "밀폐 공간에서 작업하기 전에 산소 농도를 측정한다.",
            "고소 작업을 할 때는 안전대를 착용한다.",
            "도장 작업 구역에서는 화기를 사용할 수 없다."
    };

    /** 같은 순서의 베트남어 질의. KOREAN[i]가 정답이다. */
    private static final String[] VIETNAMESE = {
            "Phải đeo thiết bị bảo hộ khi hàn.",
            "Cấm đi lại phía dưới cần cẩu.",
            "Đo nồng độ oxy trước khi làm việc trong không gian kín.",
            "Đeo dây an toàn khi làm việc trên cao.",
            "Không được dùng lửa trong khu vực sơn."
    };

    /** 같은 순서의 영어 질의. */
    private static final String[] ENGLISH = {
            "You must wear protective equipment when welding.",
            "Passing under the crane is prohibited.",
            "Measure the oxygen level before working in a confined space.",
            "Wear a safety harness when working at height.",
            "No open flame is allowed in the painting area."
    };

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @DisplayName("베트남어·영어 질의로 한국어 원문이 검색되는가")
    void 교차언어_검색() throws Exception {
        Assumptions.assumeTrue(serverUp(), "임베딩 서버(localhost:8081)가 없어 검증을 건너뜁니다.");

        // 코퍼스는 문서이므로 passage:, 질의는 query: 프리픽스를 붙인다(e5 규칙).
        List<float[]> corpus = new ArrayList<>();
        for (String ko : KOREAN) {
            corpus.add(embed("passage: " + ko));
        }

        System.out.println();
        System.out.println("=== 교차언어 검색 실측 (multilingual-e5-small / 한국어 코퍼스 " + KOREAN.length + "건) ===");

        Score vi = evaluate("베트남어", VIETNAMESE, corpus);
        Score en = evaluate("영어", ENGLISH, corpus);

        System.out.println();
        System.out.printf("%-8s | recall@1 %d/%d | 정답 평균 %.3f | 오답 평균 %.3f | 격차 %.3f%n",
                "베트남어", vi.hits, KOREAN.length, vi.correctAvg, vi.wrongAvg, vi.correctAvg - vi.wrongAvg);
        System.out.printf("%-8s | recall@1 %d/%d | 정답 평균 %.3f | 오답 평균 %.3f | 격차 %.3f%n",
                "영어", en.hits, KOREAN.length, en.correctAvg, en.wrongAvg, en.correctAvg - en.wrongAvg);
        System.out.println();

        // 교차언어가 성립하려면 최소한 정답이 오답보다 일관되게 가까워야 한다.
        assertTrue(vi.correctAvg > vi.wrongAvg,
                "베트남어 질의에서 정답 원문이 오답보다 가깝지 않다 - 교차언어가 성립하지 않는다");
        assertTrue(en.correctAvg > en.wrongAvg,
                "영어 질의에서 정답 원문이 오답보다 가깝지 않다");
    }

    private Score evaluate(String label, String[] queries, List<float[]> corpus) throws Exception {
        int hits = 0;
        double correctSum = 0, wrongSum = 0;
        int wrongCount = 0;

        System.out.println();
        System.out.println("[" + label + " 질의]");

        for (int q = 0; q < queries.length; q++) {
            float[] queryVector = embed("query: " + queries[q]);

            List<Ranked> ranked = new ArrayList<>();
            for (int d = 0; d < corpus.size(); d++) {
                double sim = RetrievalService.cosine(queryVector, corpus.get(d));
                ranked.add(new Ranked(d, sim));
                if (d == q) {
                    correctSum += sim;
                } else {
                    wrongSum += sim;
                    wrongCount++;
                }
            }
            ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());

            boolean hit = ranked.get(0).index() == q;
            if (hit) {
                hits++;
            }
            System.out.printf("  %s  %.3f  %s%n",
                    hit ? "O" : "X",
                    ranked.get(0).score(),
                    KOREAN[ranked.get(0).index()]);
        }
        return new Score(hits, correctSum / queries.length, wrongSum / wrongCount);
    }

    private boolean serverUp() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:8081/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private float[] embed(String text) throws Exception {
        String body = "{\"inputs\":" + jsonString(text) + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(EMBED_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        String response = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        String inner = response.substring(response.indexOf("[[") + 2, response.indexOf("]]"));
        String[] parts = inner.split(",");

        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record Ranked(int index, double score) {}

    private record Score(int hits, double correctAvg, double wrongAvg) {}
}
