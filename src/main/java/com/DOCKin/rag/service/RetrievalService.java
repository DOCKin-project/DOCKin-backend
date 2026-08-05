package com.DOCKin.rag.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.rag.dto.RetrievalResult;
import com.DOCKin.rag.dto.RetrievedChunk;
import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import com.DOCKin.rag.repository.DocumentChunkRepository;
import com.DOCKin.rag.repository.NearestChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 질의와 가장 가까운 근거 청크를 찾는다. RAG의 R(Retrieval)에 해당한다.
 *
 * <h3>유사도 계산은 DB에서 한다</h3>
 * Phase 1에서는 후보 벡터를 전부 애플리케이션으로 가져와 자바에서 코사인을 계산했다.
 * 10만 청크 기준 146MB를 JDBC로 옮기는 비용이 병목의 81%였고, 그 구간은 인덱스로 줄일 수 없다 —
 * 인덱스는 "어떤 행을 찾을지"의 비용이지 "찾은 것을 옮기는" 비용이 아니기 때문이다.
 *
 * <pre>
 * 애플리케이션에서 계산                4,059 ms
 * DB에서 계산 (인덱스 없이)              약 57 ms   ← 인덱스를 만들기 전에 이미 70배
 * DB에서 계산 + HNSW (워밍)              약 1.7 ms
 * </pre>
 *
 * <p>지금은 DB가 정렬과 {@code LIMIT}까지 끝내고 <b>상위 몇 건만</b> 넘어온다.
 * 그래서 본문도 함께 실어 올 수 있게 되어, "벡터만 조회 → 점수 계산 → 본문 재조회"
 * 2단계 왕복이 한 번으로 합쳐졌다. 상세는 ADR-0006 8-2.
 *
 * <h3>권한은 SQL에서 선필터한다</h3>
 * 후보를 가져오는 단계에서 이미 걸러지므로 이 클래스는 권한을 다시 판단하지 않는다.
 * 후필터를 쓰지 않는 이유는 ADR-0006 6절 참고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    /**
     * 한 원본 문서에서 가져올 수 있는 최대 청크 수.
     *
     * <p>긴 문서 하나가 top-k를 독식하면 근거가 한 문서에 쏠려 답변의 시야가 좁아진다.
     * 문서당 상한을 두어 서로 다른 원본이 섞이도록 한다.
     */
    private static final int MAX_PER_SOURCE = 2;

    /**
     * SQL에서 요청할 후보 배수. {@code topK}의 이 배수만큼 가져와 {@link #MAX_PER_SOURCE}를 적용한다.
     *
     * <p><b>이 over-fetch는 권한과 무관하다.</b> ADR-0006 6절이 거부한 것은 "권한을 나중에 거르려고"
     * 넉넉히 가져오는 방식이었다. 여기서 더 가져오는 이유는 <b>문서당 상한</b> 때문이다 —
     * 한 문서에서 나온 청크가 상위를 채우면 그만큼 버려지므로, 정확히 {@code topK}만 가져오면
     * 상한을 적용한 뒤 개수가 모자란다. 권한 필터는 여전히 SQL 안에서 걸린다.
     *
     * <p>상한이 2이므로 최악의 경우에도 배수 4면 서로 다른 문서 최소 2개에서 {@code topK}를 채운다.
     */
    private static final int CANDIDATE_MULTIPLIER = 4;

    /** 폴백에서 사용할 최대 키워드 수. 질의가 길어도 LIKE 쿼리가 무한정 늘지 않게 한다. */
    private static final int MAX_FALLBACK_KEYWORDS = 3;

    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingClient embeddingClient;

    /**
     * 최소 유사도. 이보다 낮은 청크는 근거로 쓰지 않는다.
     *
     * <p>기본값 0.0(비활성)인 이유: e5 계열은 무관한 문장 사이에서도 코사인이 높게 나오는 경향이 있어
     * 절대 임계값을 감으로 정하면 오히려 정상 근거를 버린다. <b>실측으로 분포를 본 뒤 정해야 한다</b>
     * ({@code docs/SERVICE-SCALE-ASSUMPTIONS.md}의 미실측 항목).
     *
     * <p><b>SQL이 아니라 여기서 거른다.</b> 거리 조건을 {@code WHERE}에 넣으면 HNSW 인덱스 탐색 뒤에
     * 필터로 적용되어 반환 개수가 줄고, 권한 필터와 같은 문제를 하나 더 만든다.
     * 어차피 걸러낼 대상이 상위 몇 건뿐이라 애플리케이션에서 처리하는 편이 단순하고 안전하다.
     */
    @Value("${rag.search.min-score:0.0}")
    private double minScore;

    /**
     * 근거를 찾되, 임베딩 서버 장애 시 키워드 검색으로 떨어진다.
     *
     * <p>임베딩 서버는 나중에 붙인 부가 컴포넌트다. 그것이 죽었다고 챗봇 전체가 멈추면 안 된다
     * — ADR-0001의 "Redis가 죽어도 DB가 최종 방어선"과 같은 사고방식이다.
     *
     * <p>폴백은 <b>등급이 내려간 서비스</b>이지 동등한 대체가 아니다. 벡터 검색은 "안전벨트"로
     * 물어도 "추락 방지 장비"를 찾지만 LIKE는 글자가 일치해야만 찾는다.
     * 그래서 어느 경로였는지를 {@link RetrievalResult#mode()}로 남겨 사후에 구분할 수 있게 한다.
     */
    public RetrievalResult retrieve(String query, String userId, boolean admin, int topK) {
        try {
            List<RetrievedChunk> chunks = search(query, userId, admin, topK);
            return chunks.isEmpty()
                    ? RetrievalResult.none()
                    : new RetrievalResult(RetrievalResult.RetrievalMode.VECTOR, chunks);

        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.EMBEDDING_SERVER_ERROR
                    && e.getErrorCode() != ErrorCode.EMBEDDING_DIMENSION_MISMATCH) {
                throw e;
            }
            log.warn("[RAG] 벡터 검색 실패 - 키워드 폴백으로 전환합니다: {}", e.getMessage());
            List<RetrievedChunk> chunks = keywordFallback(query, userId, admin, topK);
            return chunks.isEmpty()
                    ? RetrievalResult.none()
                    : new RetrievalResult(RetrievalResult.RetrievalMode.KEYWORD, chunks);
        }
    }

    /**
     * 질의와 가까운 근거 청크를 유사도 내림차순으로 반환한다.
     *
     * @param query  사용자 질문 원문 (프리픽스는 {@link EmbeddingClient}가 붙인다)
     * @param userId 조회 주체. 권한 선필터에 사용된다
     * @param admin  true면 권한 필터 없이 전체를 대상으로 한다
     * @param topK   최대 반환 개수
     * @throws BusinessException 임베딩 서버 호출 실패 또는 질의 벡터의 차원 불일치 시.
     *         호출자는 이를 잡아 키워드 검색으로 폴백한다
     */
    @Transactional(readOnly = true)
    public List<RetrievedChunk> search(String query, String userId, boolean admin, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        String model = embeddingClient.getModelName();
        float[] queryVector = embeddingClient.embedQuery(query);

        // 컬럼이 vector(384)로 고정되어 있어 차원이 다르면 DB가 쿼리 자체를 거부한다
        // ("different vector dimensions"). 그 예외를 그대로 흘리면 폴백 조건에 걸리지 않으므로
        // 여기서 도메인 예외로 바꿔 키워드 폴백으로 내려보낸다.
        if (queryVector.length != DocumentChunk.EMBEDDING_DIM) {
            log.error("[RAG] 질의 벡터 차원 불일치: 기대 {}, 실제 {} - 색인 모델과 질의 모델이 다릅니다.",
                    DocumentChunk.EMBEDDING_DIM, queryVector.length);
            throw new BusinessException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
        }

        String literal = toVectorLiteral(queryVector);
        int limit = topK * CANDIDATE_MULTIPLIER;

        List<NearestChunk> candidates;
        if (admin) {
            candidates = documentChunkRepository.findNearestAll(model, literal, limit);
        } else {
            // 공개 청크와 본인 소유 청크를 각각 top-n으로 뽑아 합친다.
            // 각 목록이 자기 집합의 상위 n건이므로, 합집합의 상위 n건은 이 안에 들어 있다.
            candidates = new ArrayList<>(documentChunkRepository.findNearestPublic(model, literal, limit));
            candidates.addAll(documentChunkRepository.findNearestOwned(model, userId, literal, limit));
        }
        return selectTopK(candidates, topK);
    }

    /**
     * 거리 오름차순(=유사도 내림차순)으로 정렬한 뒤, 원본 문서당 {@link #MAX_PER_SOURCE}개까지만 취한다.
     *
     * <p>DB가 이미 정렬해 보냈지만 여기서 다시 정렬하는 이유는 <b>일반 사용자 경로에서 두 목록을
     * 합치기 때문</b>이다. 각각은 정렬되어 있어도 합친 결과는 아니다.
     */
    private List<RetrievedChunk> selectTopK(List<NearestChunk> candidates, int topK) {
        List<NearestChunk> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(NearestChunk::getDistance));

        List<RetrievedChunk> selected = new ArrayList<>(topK);
        Map<String, Integer> perSource = new HashMap<>();

        for (NearestChunk candidate : sorted) {
            // pgvector의 <=> 는 코사인 "거리"다. 유사도로 쓰려면 뒤집어야 한다.
            double similarity = 1.0 - candidate.getDistance();
            if (similarity < minScore) {
                // 거리 오름차순이므로 이후는 모두 더 낮다.
                break;
            }
            String sourceKey = candidate.getSourceType() + ":" + candidate.getSourceId();
            int used = perSource.getOrDefault(sourceKey, 0);
            if (used >= MAX_PER_SOURCE) {
                continue;
            }
            perSource.put(sourceKey, used + 1);
            selected.add(new RetrievedChunk(
                    candidate.getChunkId(),
                    SourceType.valueOf(candidate.getSourceType()),
                    candidate.getSourceId(),
                    candidate.getChunkIndex(),
                    candidate.getContent(),
                    similarity));

            if (selected.size() >= topK) {
                break;
            }
        }
        return selected;
    }

    /**
     * 키워드(LIKE) 폴백. 질의를 토큰으로 쪼개 길이 2 이상인 것들로 검색한다.
     *
     * <p>질의 문장 전체를 LIKE로 던지면 절대 매치되지 않으므로 토큰 단위로 나눈다.
     * 형태소 분석기가 없어 조사가 붙은 채로 검색되는 한계가 있다 — 폴백 품질이 낮은 이유 중 하나이며,
     * ADR-0003 3-1(ngram FULLTEXT)이 겨냥하는 지점이기도 하다.
     */
    private List<RetrievedChunk> keywordFallback(String query, String userId, boolean admin, int topK) {
        String model = embeddingClient.getModelName();
        List<String> keywords = Arrays.stream(query.split("\\s+"))
                .map(t -> t.replaceAll("[^\\p{L}\\p{N}]", ""))
                .filter(t -> t.length() >= 2)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(MAX_FALLBACK_KEYWORDS)
                .toList();

        if (keywords.isEmpty()) {
            return List.of();
        }
        Pageable limit = PageRequest.of(0, topK);
        Map<Long, RetrievedChunk> merged = new LinkedHashMap<>();

        for (String keyword : keywords) {
            List<DocumentChunk> found = admin
                    ? documentChunkRepository.searchAllByKeyword(model, keyword, limit)
                    : documentChunkRepository.searchByKeyword(model, userId, keyword, limit);

            for (DocumentChunk chunk : found) {
                // 폴백에는 유사도 점수가 없다. 0.0으로 두어 벡터 결과와 혼동되지 않게 한다.
                merged.putIfAbsent(chunk.getChunkId(), new RetrievedChunk(
                        chunk.getChunkId(), chunk.getSourceType(), chunk.getSourceId(),
                        chunk.getChunkIndex(), chunk.getContent(), 0.0));
            }
            if (merged.size() >= topK) {
                break;
            }
        }
        return merged.values().stream().limit(topK).toList();
    }

    /**
     * float 배열을 pgvector가 받는 텍스트 표현({@code "[0.1,-0.2,...]"})으로 만든다.
     *
     * <p>JDBC 바인딩 파라미터에는 타입 정보가 없어 드라이버가 {@code vector}로 보낼 방법이 없다.
     * 문자열로 넘기고 SQL에서 {@code CAST(? AS vector)}로 타입을 지정한다.
     */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * 코사인 유사도.
     *
     * <p><b>검색 경로에서는 더 이상 쓰지 않는다</b> — 계산이 DB로 넘어갔다.
     * {@code BruteForceSearchBenchmarkTest}가 Phase 1 기준선을 재현하는 데 사용하며,
     * ANN recall을 잴 때의 정답 계산식이기도 하다.
     *
     * <p>TEI는 기본적으로 정규화된 벡터를 반환하므로 내적만으로도 같은 값이 나오지만,
     * 정규화 여부가 모델/설정에 따라 달라질 수 있어 노름까지 계산한다.
     */
    static double cosine(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
