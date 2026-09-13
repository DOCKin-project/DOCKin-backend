package com.DOCKin.rag.chunking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 측정: {@code SENTENCE_LOOKBACK}은 왜 120인가?
 *
 * <h3>배경</h3>
 * {@code TARGET_CHARS}(500)와 {@code OVERLAP_CHARS}(50)는 토큰 비율 실측이라는 근거가 주석에 있는데
 * {@code SENTENCE_LOOKBACK}만 근거가 없었다(백로그 P0-10). 감으로 정한 값이 주석 없이 남아 있으면
 * 나중에 누구도 못 바꾼다 -- 바꿔도 되는지 판단할 근거가 없기 때문이다.
 *
 * <h3>무엇을 재는가 -- 두 가지다</h3>
 * "강제 절단 비율"만 재면 답이 <b>"탐색 거리를 최대로 키워라"</b>로 나온다. 항상 줄어들기 때문이다.
 * 그런데 탐색 거리를 키우면 경계를 찾느라 <b>더 앞에서 끊게 되어 청크가 짧아진다.</b>
 * 청크가 짧아지면 같은 코퍼스가 더 많은 청크로 쪼개지고, 저장·임베딩·검색 비용이 모두 늘어난다.
 * 그래서 <b>강제 절단 비율과 평균 청크 길이를 함께</b> 본다.
 *
 * <h3>합성 코퍼스다</h3>
 * 실제 작업일지가 저장소에 없으므로 문장 길이 분포를 제어한 합성 텍스트를 쓴다.
 * {@code BruteForceSearchBenchmarkTest}가 합성 적재로 실측한 것과 같은 방식이며,
 * <b>절대값이 아니라 값 사이의 관계</b>를 보는 것이 목적이다.
 * 실데이터가 쌓이면 같은 테스트를 그대로 돌려 다시 판단할 수 있다.
 */
class SentenceLookbackMeasurementTest {

    /** 재현 가능해야 하므로 시드를 고정한다. */
    private static final long SEED = 42L;

    private static final int DOCS_PER_STYLE = 40;
    private static final int DOC_CHARS = 4_000;

    private static final int[] LOOKBACKS = {0, 40, 80, 120, 160, 200, 250, 400};

    @Test
    @DisplayName("탐색 거리별 강제 절단 비율과 평균 청크 길이를 잰다")
    void 탐색거리_스윕() {
        List<Style> styles = List.of(
                new Style("규정 문투 (짧은 문장)", 35, true),
                new Style("작업일지 (보통)", 70, true),
                new Style("긴 서술", 140, true),
                new Style("STT 받아쓰기 (문장부호 없음)", 0, false)
        );

        System.out.println("\n=== SENTENCE_LOOKBACK 스윕 ===");
        System.out.printf("문서 %d개/스타일, 문서당 약 %d자, TARGET_CHARS=%d%n",
                DOCS_PER_STYLE, DOC_CHARS, FixedSizeChunkingStrategy.TARGET_CHARS);

        for (Style style : styles) {
            List<String> corpus = buildCorpus(style);
            System.out.printf("%n[%s]%n", style.name());
            System.out.println("  lookback | 강제절단 | 평균청크(자) | 청크수");
            System.out.println("  ---------|----------|--------------|-------");

            double previousForced = Double.MAX_VALUE;
            for (int lookback : LOOKBACKS) {
                Measurement m = measure(corpus, lookback);
                System.out.printf("  %8d | %7.1f%% | %12.0f | %6d%n",
                        lookback, m.forcedRatio() * 100, m.averageChunkChars(), m.chunkCount());

                // 탐색 거리를 늘렸는데 강제 절단이 늘어나면 로직이 잘못된 것이다.
                assertTrue(m.forcedRatio() <= previousForced + 1e-9,
                        "탐색 거리를 늘렸는데 강제 절단이 증가했다: lookback=" + lookback);
                previousForced = m.forcedRatio();
            }
        }

        // ① 주 사용처(작업일지 문투)에서 120이 충분한가.
        List<String> workLog = buildCorpus(new Style("작업일지 (보통)", 70, true));
        Measurement workLogAt120 = measure(workLog, FixedSizeChunkingStrategy.DEFAULT_SENTENCE_LOOKBACK);
        Measurement workLogAt400 = measure(workLog, 400);

        assertTrue(workLogAt120.forcedRatio() < 0.05,
                "작업일지 문투에서 120은 강제 절단을 5% 미만으로 유지해야 한다. 실제: " + workLogAt120.forcedRatio());

        // ② 값을 더 키워도 얻는 것이 없다 -- 뒤에서부터 찾아 가장 가까운 경계를 쓰기 때문에
        //    이미 경계를 찾은 절단은 창을 넓혀도 위치가 바뀌지 않는다.
        assertTrue(workLogAt400.averageChunkChars() == workLogAt120.averageChunkChars()
                        && workLogAt400.chunkCount() == workLogAt120.chunkCount(),
                "강제 절단이 이미 0%면 창을 넓혀도 결과가 같아야 한다");

        // ③ 대가는 '구조되는 절단'에만 붙는다. 강제 절단이 남아 있는 문투에서만 청크가 짧아진다.
        List<String> longForm = buildCorpus(new Style("긴 서술", 140, true));
        Measurement longAt120 = measure(longForm, FixedSizeChunkingStrategy.DEFAULT_SENTENCE_LOOKBACK);
        Measurement longAt200 = measure(longForm, 200);

        assertTrue(longAt200.forcedRatio() < longAt120.forcedRatio(),
                "긴 서술에서는 120이 부족해 창을 넓히면 강제 절단이 줄어야 한다");
        assertTrue(longAt200.averageChunkChars() < longAt120.averageChunkChars(),
                "강제 절단을 구제하는 만큼 청크는 짧아진다(트레이드오프가 존재한다는 확인)");

        System.out.printf("%n요약%n"
                        + "  작업일지 문투: 120에서 강제절단 %.1f%%. 400으로 키워도 평균 %.0f자로 동일 -- 더 얻을 것이 없다.%n"
                        + "  긴 서술:      120에서 %.1f%% (평균 %.0f자) -> 200에서 %.1f%% (평균 %.0f자). "
                        + "구제하는 대가로 청크가 %.0f%% 짧아진다.%n",
                workLogAt120.forcedRatio() * 100, workLogAt400.averageChunkChars(),
                longAt120.forcedRatio() * 100, longAt120.averageChunkChars(),
                longAt200.forcedRatio() * 100, longAt200.averageChunkChars(),
                (1 - longAt200.averageChunkChars() / longAt120.averageChunkChars()) * 100);
    }

    @Test
    @DisplayName("문장부호가 없으면 탐색 거리를 아무리 늘려도 소용없다")
    void 문장부호_없는_텍스트() {
        List<String> corpus = buildCorpus(new Style("STT", 0, false));

        Measurement at120 = measure(corpus, 120);
        Measurement at400 = measure(corpus, 400);

        // 경계 문자가 하나도 없으므로 어떤 탐색 거리에서도 전부 강제 절단이다.
        assertTrue(at120.forcedRatio() > 0.99, "문장부호가 없으면 전부 강제 절단이어야 한다");
        assertTrue(at400.forcedRatio() > 0.99, "탐색 거리를 늘려도 마찬가지여야 한다");

        System.out.printf("%n문장부호 없는 텍스트: lookback 120 -> %.0f%%, 400 -> %.0f%% (변화 없음)%n",
                at120.forcedRatio() * 100, at400.forcedRatio() * 100);
    }

    private Measurement measure(List<String> corpus, int lookback) {
        FixedSizeChunkingStrategy strategy = new FixedSizeChunkingStrategy(lookback);

        int boundaryCuts = 0;
        int forcedCuts = 0;
        int chunkCount = 0;
        long totalChars = 0;

        for (String doc : corpus) {
            FixedSizeChunkingStrategy.SplitStats stats = strategy.splitWithStats(doc);
            boundaryCuts += stats.boundaryCuts();
            forcedCuts += stats.forcedCuts();
            chunkCount += stats.chunks().size();
            for (String chunk : stats.chunks()) {
                totalChars += chunk.length();
            }
        }

        return new Measurement(boundaryCuts, forcedCuts, chunkCount, totalChars);
    }

    /**
     * 문장 길이 분포를 제어한 합성 문서를 만든다.
     *
     * <p>{@code meanSentenceChars}를 중심으로 흔들어 문장을 이어 붙인다.
     * 실제 텍스트의 의미는 청킹에 영향을 주지 않는다 -- <b>경계 문자의 위치만이 변수</b>다.
     */
    private List<String> buildCorpus(Style style) {
        Random random = new Random(SEED);
        List<String> docs = new ArrayList<>(DOCS_PER_STYLE);

        for (int d = 0; d < DOCS_PER_STYLE; d++) {
            StringBuilder doc = new StringBuilder(DOC_CHARS + 200);
            while (doc.length() < DOC_CHARS) {
                if (!style.punctuated()) {
                    doc.append(filler(random, 40)).append(' ');
                    continue;
                }
                int length = Math.max(8, (int) (style.meanSentenceChars() * (0.5 + random.nextDouble())));
                doc.append(filler(random, length));
                // 개조식 작업일지는 줄바꿈으로도 끊긴다.
                doc.append(random.nextInt(4) == 0 ? '\n' : '.');
                doc.append(' ');
            }
            docs.add(doc.toString());
        }
        return docs;
    }

    /** 경계 문자가 섞이지 않은 채움 텍스트. 한글 음절을 무작위로 고른다. */
    private String filler(Random random, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            // 공백을 간간이 섞어 실제 텍스트에 가깝게 만든다. 공백은 경계 문자가 아니다.
            sb.append(random.nextInt(7) == 0 ? ' ' : (char) ('가' + random.nextInt(100)));
        }
        return sb.toString();
    }

    private record Style(String name, int meanSentenceChars, boolean punctuated) {
    }

    private record Measurement(int boundaryCuts, int forcedCuts, int chunkCount, long totalChars) {

        double forcedRatio() {
            int total = boundaryCuts + forcedCuts;
            return total == 0 ? 0.0 : (double) forcedCuts / total;
        }

        double averageChunkChars() {
            return chunkCount == 0 ? 0.0 : (double) totalChars / chunkCount;
        }
    }
}
