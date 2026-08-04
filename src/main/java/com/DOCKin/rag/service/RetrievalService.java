package com.DOCKin.rag.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.rag.dto.RetrievalResult;
import com.DOCKin.rag.dto.RetrievedChunk;
import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.repository.ChunkVector;
import com.DOCKin.rag.repository.DocumentChunkRepository;
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
 * <h3>Phase 1은 브루트포스(정확 최근접)다</h3>
 * ANN 인덱스 없이 후보 전체와 코사인 유사도를 계산한다. 근사가 아니므로 <b>recall이 100%</b>이고,
 * Phase 2에서 ANN(pgvector HNSW)을 도입할 때 이 결과가 정답(ground truth) 기준선이 된다.
 * 챗봇 트래픽이 약 0.02 TPS라 지연시간 요구가 사실상 없어 성립하는 선택이다.
 *
 * <h3>권한은 SQL에서 선필터한다</h3>
 * 후보를 가져오는 단계에서 이미 걸러지므로, 이 클래스는 권한을 다시 판단하지 않는다.
 * 후필터를 쓰지 않는 이유는 {@link DocumentChunkRepository#findSearchTargets} 주석 참고.
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
     * 질의와 가까운 근거 청크를 유사도 내림차순으로 반환한다.
     *
     * @param query  사용자 질문 원문 (프리픽스는 {@link EmbeddingClient}가 붙인다)
     * @param userId 조회 주체. 권한 선필터에 사용된다
     * @param admin  true면 권한 필터 없이 전체를 대상으로 한다
     * @param topK   최대 반환 개수
     * @throws com.DOCKin.global.error.BusinessException 임베딩 서버 호출 실패 시.
     *         호출자는 이를 잡아 기존 LIKE 검색으로 폴백한다
     */
    @Transactional(readOnly = true)
    public List<RetrievedChunk> search(String query, String userId, boolean admin, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        String model = embeddingClient.getModelName();
        float[] queryVector = embeddingClient.embedQuery(query);

        List<ChunkVector> candidates = admin
                ? documentChunkRepository.findAllSearchTargets(model)
                : documentChunkRepository.findSearchTargets(model, userId);

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Scored> scored = score(candidates, queryVector);
        List<Scored> selected = selectTopK(scored, topK);
        if (selected.isEmpty()) {
            return List.of();
        }

        return attachContent(selected);
    }

    /** 후보 전체와 코사인 유사도를 계산한다. 차원이 다른 청크는 건너뛴다. */
    private List<Scored> score(List<ChunkVector> candidates, float[] queryVector) {
        List<Scored> scored = new ArrayList<>(candidates.size());
        int skipped = 0;

        for (ChunkVector candidate : candidates) {
            // 모델 교체 과도기에는 차원이 다른 청크가 공존할 수 있다. 비교 자체가 불가능하므로 제외한다.
            if (candidate.embeddingDim() == null || candidate.embeddingDim() != queryVector.length) {
                skipped++;
                continue;
            }
            double similarity = cosine(queryVector, EmbeddingClient.toFloats(candidate.embedding()));
            if (similarity >= minScore) {
                scored.add(new Scored(candidate, similarity));
            }
        }
        if (skipped > 0) {
            log.warn("[RAG] 차원 불일치로 제외한 청크 {}건 - 재색인이 필요할 수 있습니다.", skipped);
        }
        return scored;
    }

    /** 유사도 내림차순으로 정렬한 뒤, 원본 문서당 {@link #MAX_PER_SOURCE}개까지만 취한다. */
    private List<Scored> selectTopK(List<Scored> scored, int topK) {
        scored.sort(Comparator.comparingDouble(Scored::similarity).reversed());

        List<Scored> selected = new ArrayList<>(topK);
        Map<String, Integer> perSource = new HashMap<>();

        for (Scored s : scored) {
            String sourceKey = s.vector().sourceType() + ":" + s.vector().sourceId();
            int used = perSource.getOrDefault(sourceKey, 0);
            if (used >= MAX_PER_SOURCE) {
                continue;
            }
            perSource.put(sourceKey, used + 1);
            selected.add(s);
            if (selected.size() >= topK) {
                break;
            }
        }
        return selected;
    }

    /**
     * 선택된 청크에 대해서만 본문을 조회한다.
     * 후보 전체의 본문을 메모리에 올리지 않기 위해 투영과 분리해둔 단계다.
     */
    private List<RetrievedChunk> attachContent(List<Scored> selected) {
        List<Long> ids = selected.stream().map(s -> s.vector().chunkId()).toList();

        Map<Long, DocumentChunk> byId = new HashMap<>();
        for (DocumentChunk chunk : documentChunkRepository.findByChunkIdIn(ids)) {
            byId.put(chunk.getChunkId(), chunk);
        }

        List<RetrievedChunk> result = new ArrayList<>(selected.size());
        for (Scored s : selected) {
            DocumentChunk chunk = byId.get(s.vector().chunkId());
            if (chunk == null) {
                // 검색 도중 재색인으로 삭제된 경우. 근거가 없으므로 조용히 제외한다.
                continue;
            }
            result.add(new RetrievedChunk(
                    chunk.getChunkId(),
                    chunk.getSourceType(),
                    chunk.getSourceId(),
                    chunk.getChunkIndex(),
                    chunk.getContent(),
                    s.similarity()));
        }
        return result;
    }

    /**
     * 코사인 유사도.
     *
     * <p>TEI는 기본적으로 정규화된 벡터를 반환하므로 내적만으로도 같은 값이 나오지만,
     * 정규화 여부가 모델/설정에 따라 달라질 수 있어 노름까지 계산한다.
     * 벡터가 384차원이라 비용 차이는 무시할 수준이다.
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

    private record Scored(ChunkVector vector, double similarity) {}
}
