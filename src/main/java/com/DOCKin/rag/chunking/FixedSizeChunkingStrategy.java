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

    /** 문장 경계를 뒤로 탐색할 최대 거리. 이 안에 못 찾으면 강제로 자른다. */
    private static final int SENTENCE_LOOKBACK = 120;

    private static final Set<SourceType> SUPPORTED = EnumSet.of(
            SourceType.WORK_LOG,
            SourceType.WORK_LOG_TRANSLATION,
            SourceType.SAFETY_COURSE,
            SourceType.CHECKLIST_ITEM
    );

    @Override
    public boolean supports(SourceType sourceType) {
        return SUPPORTED.contains(sourceType);
    }

    @Override
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String normalized = text.strip();
        if (normalized.length() <= TARGET_CHARS) {
            chunks.add(normalized);
            return chunks;
        }

        int pos = 0;
        int len = normalized.length();

        while (pos < len) {
            int hardEnd = Math.min(pos + TARGET_CHARS, len);
            int end = hardEnd;

            // 마지막 청크가 아니면 문장 경계에서 끊는다.
            if (hardEnd < len) {
                int boundary = findSentenceEnd(normalized, pos, hardEnd);
                if (boundary > pos) {
                    end = boundary;
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

        return chunks;
    }

    /**
     * {@code hardEnd}에서 뒤로 최대 {@link #SENTENCE_LOOKBACK}자 범위에서 문장 종결 위치를 찾는다.
     *
     * @return 종결 문자 <b>다음</b> 인덱스. 못 찾으면 -1
     */
    private int findSentenceEnd(String text, int from, int hardEnd) {
        int limit = Math.max(from, hardEnd - SENTENCE_LOOKBACK);
        for (int i = hardEnd - 1; i >= limit; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '。') {
                return i + 1;
            }
        }
        return -1;
    }
}
