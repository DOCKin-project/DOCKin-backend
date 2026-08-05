package com.DOCKin.rag.chunking;

import com.DOCKin.rag.model.SourceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 고정 크기 + 문장 경계 우선 청킹. Phase 1의 기본 전략이다.
 *
 * <h3>크기 산정 근거 (실측)</h3>
 * 임베딩 모델 {@code multilingual-e5-small}의 입력 상한은 <b>512 토큰</b>이다.
 * 이는 파라미터가 아니라 학습 시 위치 임베딩을 512개만 만들어둔 구조적 상한이라 늘릴 수 없다.
 *
 * <p>TEI {@code /tokenize}로 실측한 한국어 토큰 비율:
 * <pre>
 *   짧은 문장   11자 →  8토큰  (0.73 토큰/글자)  ← 최악값
 *   작업일지    84자 → 47토큰  (0.56)
 *   규정 문투   69자 → 39토큰  (0.57)
 *   영어(비교) 112자 → 31토큰  (0.28)
 * </pre>
 * 한국어는 조사·어미 때문에 영어의 약 2배 토큰을 쓴다.
 * 최악값 0.73을 기준으로 {@code 500자 × 0.73 ≈ 365토큰}이라 512 상한에 안전 마진이 남는다.
 *
 * <p>상한에 맞춰 700자로 잡지 않은 이유: 숫자·기호·영문 약어가 많이 섞인 문단은
 * 토큰 비율이 더 올라갈 수 있고, 잘리면(auto-truncate) 뒷부분이 검색에서 통째로 사라진다.
 * 조용히 유실되는 쪽이 청크가 조금 많아지는 쪽보다 나쁘다.
 */
@Component
public class FixedSizeChunkingStrategy implements ChunkingStrategy {

    /** 목표 청크 길이(자). 실측 기준 약 365토큰. */
    static final int TARGET_CHARS = 500;

    /** 청크 간 겹치는 길이. 경계에서 문맥이 끊겨 검색이 실패하는 것을 완화한다. */
    static final int OVERLAP_CHARS = 50;

    /**
     * 문장 경계를 뒤로 탐색할 최대 거리. 이 안에 못 찾으면 강제로 자른다.
     *
     * <h4>근거 (실측 — {@code SentenceLookbackMeasurementTest}, 6-6절)</h4>
     * 합성 코퍼스에서 탐색 거리를 스윕한 결과 <b>필요한 거리는 평균 문장 길이의 약 1.7배</b>다
     * (35자→80, 70자→120, 140자→200에서 강제 절단 0%).
     * <b>즉 120은 "평균 문장 길이 70자"를 가정한 값이다.</b>
     *
     * <p><b>더 크게 잡으면 되는 것 아닌가 — 아니다.</b> 넓히면 강제 절단이 경계 절단으로 바뀌는 만큼
     * 더 앞에서 끊게 되어 청크가 짧아진다(긴 서술 기준 120→200에서 평균 444자→411자, 8% 감소).
     * 청크가 늘면 저장·임베딩·검색 비용이 함께 는다.
     *
     * <p>반대로 이미 경계를 찾은 절단은 창을 넓혀도 <b>위치가 바뀌지 않는다</b> —
     * {@link #findSentenceEnd}가 뒤에서부터 훑어 가장 가까운 경계를 쓰기 때문이다.
     * 그래서 작업일지 문투에서는 120과 400의 결과가 완전히 같다.
     *
     * <p><b>한계:</b> 종결 부호가 없는 텍스트(STT 받아쓰기)에서는 어떤 값을 줘도 100% 강제 절단이다.
     * 이 프로젝트에는 STT 경로가 있으므로({@code ai} 패키지) 해당 텍스트에서 이 파라미터는 무의미하며,
     * 필요하다면 탐색 거리가 아니라 문장 분리 수단 자체를 바꿔야 한다.
     */
    static final int DEFAULT_SENTENCE_LOOKBACK = 120;

    private static final Set<SourceType> SUPPORTED = EnumSet.of(
            SourceType.WORK_LOG,
            SourceType.WORK_LOG_TRANSLATION,
            SourceType.SAFETY_COURSE,
            SourceType.CHECKLIST_ITEM
    );

    private final int sentenceLookback;

    public FixedSizeChunkingStrategy() {
        this(DEFAULT_SENTENCE_LOOKBACK);
    }

    /** 탐색 거리를 바꿔가며 재기 위한 생성자. 프로덕션은 기본 생성자를 쓴다. */
    FixedSizeChunkingStrategy(int sentenceLookback) {
        this.sentenceLookback = sentenceLookback;
    }

    @Override
    public boolean supports(SourceType sourceType) {
        return SUPPORTED.contains(sourceType);
    }

    @Override
    public List<String> split(String text) {
        return splitWithStats(text).chunks();
    }

    /**
     * {@link #split}과 같은 일을 하되 경계 판정 결과를 함께 돌려준다.
     *
     * <p>측정 전용이다. 검사 로직을 테스트에 복제하면 <b>측정 대상과 다른 코드를 재게 되므로</b>
     * 프로덕션 경로에서 세고, {@code split}은 이 결과의 청크만 꺼내 쓴다.
     */
    SplitStats splitWithStats(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return new SplitStats(chunks, 0, 0);
        }

        String normalized = text.strip();
        if (normalized.length() <= TARGET_CHARS) {
            chunks.add(normalized);
            return new SplitStats(chunks, 0, 0);
        }

        int pos = 0;
        int len = normalized.length();
        int boundaryCuts = 0;
        int forcedCuts = 0;

        while (pos < len) {
            int hardEnd = Math.min(pos + TARGET_CHARS, len);
            int end = hardEnd;

            // 마지막 청크가 아니면 문장 경계에서 끊는다.
            if (hardEnd < len) {
                int boundary = findSentenceEnd(normalized, pos, hardEnd);
                if (boundary > pos) {
                    end = boundary;
                    boundaryCuts++;
                } else {
                    forcedCuts++;
                }
            }

            String chunk = normalized.substring(pos, end).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            if (end >= len) {
                break;
            }
            // 오버랩만큼 되감되, 반드시 전진시켜 무한 루프를 막는다.
            pos = Math.max(end - OVERLAP_CHARS, pos + 1);
        }

        return new SplitStats(chunks, boundaryCuts, forcedCuts);
    }

    /**
     * {@code hardEnd}에서 뒤로 최대 {@link #sentenceLookback}자 범위에서 문장 종결 위치를 찾는다.
     *
     * @return 종결 문자 <b>다음</b> 인덱스. 못 찾으면 -1
     */
    private int findSentenceEnd(String text, int from, int hardEnd) {
        int limit = Math.max(from, hardEnd - sentenceLookback);
        for (int i = hardEnd - 1; i >= limit; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '。') {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * @param chunks       분할 결과
     * @param boundaryCuts 문장 경계에서 끊은 횟수
     * @param forcedCuts   경계를 못 찾아 강제 절단한 횟수
     */
    record SplitStats(List<String> chunks, int boundaryCuts, int forcedCuts) {

        /** 절단 지점 중 강제 절단이 차지하는 비율. 절단이 없었으면 0. */
        double forcedRatio() {
            int total = boundaryCuts + forcedCuts;
            return total == 0 ? 0.0 : (double) forcedCuts / total;
        }
    }
}
