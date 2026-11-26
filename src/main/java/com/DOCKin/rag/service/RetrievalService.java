package com.DOCKin.rag.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.rag.dto.RetrievalResult;
import com.DOCKin.rag.dto.RetrievedChunk;
import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import com.DOCKin.rag.repository.KeywordChunk;
import com.DOCKin.rag.repository.KeywordChunkJdbcRepository;
import com.DOCKin.rag.repository.NearestChunk;
import com.DOCKin.rag.repository.NearestChunkJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * 폴백에서 사용할 최대 키워드 수. 질의가 길어도 {@code LIKE} 조건이 무한정 늘지 않게 한다.
     *
     * <p>3이었을 때는 "CO2 용접기 송급 롤러 이상 진동"에서 부품·증상 단어가 잘려 나갔다(#186).
     * 키워드는 한 쿼리 안의 조건으로 들어가므로 늘려도 왕복은 한 번이다.
     */
    static final int MAX_FALLBACK_KEYWORDS = 8;

    /** SQL 경로(키워드 폴백). 맞은 키워드 수로 순위를 매긴다(#186). */
    private final KeywordChunkJdbcRepository keywordChunkJdbcRepository;
    /** SQL 경로(벡터 검색). 둘을 나눈 이유는 {@link NearestChunkJdbcRepository} 참고. */
    private final NearestChunkJdbcRepository nearestChunkJdbcRepository;
    private final EmbeddingClient embeddingClient;
    /** 재순위 모델 서버. {@code rag.search.rerank.enabled}일 때만 부른다. */
    private final RerankClient rerankClient;
    /** 같은 구역 승인분(ADR-0012)을 고르려고 조회자의 구역을 읽는다. */
    private final MemberRepository memberRepository;

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
     * 하이브리드 검색 — 벡터 후보와 키워드 후보를 순위로 합친다(RRF).
     *
     * <p>벡터는 "안전벨트"로 물어 "추락 방지 장비"를 찾지만, 호기 번호·약어·부품명처럼 <b>글자가 그대로 맞아야
     * 하는 질문</b>에는 약하다(ADR-0013 2단계 3, #203). 키워드 경로는 반대다. 둘을 합치면 서로의 빈칸을 메운다.
     *
     * <p><b>기본은 꺼짐이다.</b> 켜면 질문마다 {@code LIKE} 전체 훑기가 한 번 붙는다(인덱스 없음, ADR-0002 2-2).
     * 품질(평가셋 Hit@5·P@5)과 지연을 같은 표로 잰 뒤에 켠다 `[미측정]`.
     */
    @Value("${rag.search.hybrid.enabled:false}")
    private boolean hybridEnabled;

    /**
     * RRF 상수 k. 순위 r의 점수는 {@code 1 / (k + r)}다. k가 크면 상위와 하위의 점수 차가 줄어
     * 한쪽 목록 1위가 다른 쪽 여러 순위를 이기기 어려워진다. 60은 RRF 원 논문(Cormack 2009)과
     * 검색 엔진들의 기본값이다 — 이 코퍼스에서 맞는지는 평가셋으로 스윕한다.
     */
    @Value("${rag.search.hybrid.rrf-k:60}")
    private int rrfK;

    /**
     * 재순위(cross-encoder). 기본은 꺼짐 — 켜면 검색마다 재순위 서버 호출이 하나 붙는다.
     * 모델·지연·품질은 평가셋으로 잰 뒤에 켠다 `[미측정]`.
     */
    @Value("${rag.search.rerank.enabled:false}")
    private boolean rerankEnabled;

    /** 재순위에 넘길 후보 수. 많을수록 정확하고 느리다. */
    @Value("${rag.search.rerank.candidates:20}")
    private int rerankCandidates;

    /** 재순위 점수(0~1) 하한. 0.0이면 거르지 않는다. 근거 없음 판정(#203)의 수단 — 분포를 본 뒤 정한다. */
    @Value("${rag.search.rerank.min-score:0.0}")
    private double rerankMinScore;

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
                    : new RetrievalResult(hybridEnabled
                            ? RetrievalResult.RetrievalMode.HYBRID
                            : RetrievalResult.RetrievalMode.VECTOR, chunks);

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
        String area = admin ? null : areaOf(userId);
        if (admin) {
            candidates = nearestChunkJdbcRepository.findNearestAll(model, literal, limit);
        } else {
            // 공개 청크, 본인 소유 청크, 같은 구역의 승인분(ADR-0012)을 각각 top-n으로 뽑아 합친다.
            // 각 목록이 자기 집합의 상위 n건이므로, 합집합의 상위 n건은 이 안에 들어 있다.
            // 세 집합은 visibility 값(PUBLIC/OWNER/AREA)으로 나뉘어 같은 청크가 두 번 오지 않는다.
            candidates = new ArrayList<>(nearestChunkJdbcRepository.findNearestPublic(model, literal, limit));
            candidates.addAll(nearestChunkJdbcRepository.findNearestOwned(model, userId, literal, limit));
            if (area != null) {
                candidates.addAll(nearestChunkJdbcRepository.findNearestInArea(model, area, literal, limit));
            }
        }
        List<Fused> pool = hybridEnabled
                ? fusedPool(candidates, lexicalCandidates(query, model, userId, area, admin, limit))
                : vectorPool(candidates);
        if (rerankEnabled) {
            pool = rerank(query, pool);
        }
        return cap(pool, topK);
    }

    /**
     * 후보 상위 {@code rerankCandidates}개를 재순위 모델로 다시 줄 세운다(ADR-0013 2단계 4).
     *
     * <ul>
     *   <li>재순위는 <b>문서당 상한을 걸기 전</b>에 한다. 상한을 먼저 걸면 재순위가 올려 줄 청크가 이미 빠져 있다.</li>
     *   <li>{@code rerank.min-score}보다 낮은 후보는 버린다 — 전부 낮으면 근거 없음이 되고, 챗봇은 "찾지 못했다"고 답한다(#203).
     *       기본은 꺼짐(0.0). 값은 평가셋의 답 있음/답 없음 점수 분포를 보고 정한다.</li>
     *   <li>서버가 실패하면 재순위 전 순서를 그대로 쓴다.</li>
     * </ul>
     */
    private List<Fused> rerank(String query, List<Fused> pool) {
        if (pool.isEmpty()) {
            return pool;
        }
        List<Fused> head = new ArrayList<>(pool.subList(0, Math.min(rerankCandidates, pool.size())));
        Optional<double[]> scores = rerankClient.score(query, head.stream().map(f -> f.content).toList());
        if (scores.isEmpty()) {
            return pool;
        }
        double[] values = scores.get();
        for (int i = 0; i < head.size(); i++) {
            head.get(i).rerankScore = values[i];
        }
        List<Fused> kept = new ArrayList<>(head.stream().filter(f -> f.rerankScore >= rerankMinScore).toList());
        // 정렬은 안정적이라 점수가 같으면 재순위 전 순서가 남는다.
        kept.sort(Comparator.comparingDouble((Fused f) -> f.rerankScore).reversed());
        return kept;
    }

    /**
     * 하이브리드의 키워드 후보. 폴백과 같은 쿼리·같은 권한 범위다(공개·본인·같은 구역 승인분).
     *
     * <p>키워드 쿼리가 실패해도 검색 전체를 실패시키지 않는다 — 벡터 결과만으로 답한다.
     * 하이브리드는 정상 경로의 <b>보강</b>이지 필수 경로가 아니다.
     */
    private List<KeywordChunk> lexicalCandidates(String query, String model, String userId, String area,
                                                 boolean admin, int limit) {
        List<String> keywords = fallbackKeywords(query);
        if (keywords.isEmpty()) {
            return List.of();
        }
        try {
            return admin
                    ? keywordChunkJdbcRepository.findAll(model, keywords, limit)
                    : keywordChunkJdbcRepository.findVisible(model, userId, area, keywords, limit);
        } catch (RuntimeException e) {
            log.warn("[RAG] 하이브리드 키워드 후보 조회 실패 - 벡터 결과만 씁니다: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 벡터 후보와 키워드 후보를 RRF(Reciprocal Rank Fusion)로 합친다.
     *
     * <p>점수 = Σ {@code 1 / (k + 순위)}. 순위만 쓰고 원점수(코사인·맞은 개수)는 쓰지 않는다 —
     * 두 점수는 단위가 달라 더하면 한쪽이 다른 쪽을 지배한다. 양쪽에 다 있는 청크가 위로 온다.
     *
     * <ul>
     *   <li>{@code min-score}는 벡터 후보에만 건다. 키워드로 맞은 청크는 유사도가 낮아도 남긴다 — 그게 하이브리드의 목적이다.</li>
     *   <li>응답의 {@code score}는 RRF 점수가 아니라 <b>코사인 유사도</b>(키워드로만 온 청크는 0.0)다.
     *       {@code chat_history}·SLO가 이 값을 유사도로 읽는다.</li>
     *   <li>문서당 상한({@link #MAX_PER_SOURCE})은 합친 뒤에 건다.</li>
     * </ul>
     */
    List<RetrievedChunk> fuse(List<NearestChunk> vector, List<KeywordChunk> lexical, int topK) {
        return cap(fusedPool(vector, lexical), topK);
    }

    private List<Fused> fusedPool(List<NearestChunk> vector, List<KeywordChunk> lexical) {
        List<NearestChunk> sortedVector = new ArrayList<>(vector);
        sortedVector.sort(Comparator.comparingDouble(NearestChunk::distance));

        Map<Long, Fused> fused = new LinkedHashMap<>();
        int rank = 0;
        for (NearestChunk c : sortedVector) {
            double similarity = 1.0 - c.distance();
            if (similarity < minScore) {
                break;
            }
            rank++;
            Fused f = fused.computeIfAbsent(c.chunkId(), id -> Fused.of(c));
            f.similarity = similarity;
            f.score += 1.0 / (rrfK + rank);
        }
        rank = 0;
        for (KeywordChunk c : lexical) {
            rank++;
            Fused f = fused.computeIfAbsent(c.chunkId(), id -> new Fused(
                    c.chunkId(), c.sourceType(), c.sourceId(), c.chunkIndex(), c.content()));
            f.score += 1.0 / (rrfK + rank);
        }

        List<Fused> ordered = new ArrayList<>(fused.values());
        ordered.sort(Comparator.comparingDouble((Fused f) -> f.score).reversed()
                .thenComparing(Comparator.comparingDouble((Fused f) -> f.similarity).reversed())
                .thenComparing(Comparator.comparingLong((Fused f) -> f.chunkId).reversed()));
        return ordered;
    }

    /**
     * 벡터 후보만의 순서. 거리 오름차순(=유사도 내림차순)이고 {@code min-score} 아래는 자른다.
     *
     * <p>DB가 이미 정렬해 보냈지만 여기서 다시 정렬하는 이유는 <b>일반 사용자 경로에서 여러 목록을
     * 합치기 때문</b>이다. 각각은 정렬되어 있어도 합친 결과는 아니다.
     */
    private List<Fused> vectorPool(List<NearestChunk> candidates) {
        List<NearestChunk> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(NearestChunk::distance));
        List<Fused> pool = new ArrayList<>(sorted.size());
        for (NearestChunk c : sorted) {
            // pgvector의 <=> 는 코사인 "거리"다. 유사도로 쓰려면 뒤집어야 한다.
            double similarity = 1.0 - c.distance();
            if (similarity < minScore) {
                // 거리 오름차순이므로 이후는 모두 더 낮다.
                break;
            }
            Fused f = Fused.of(c);
            f.similarity = similarity;
            pool.add(f);
        }
        return pool;
    }

    /** 순서대로 훑으며 원본 문서당 {@link #MAX_PER_SOURCE}개까지, 모두 {@code topK}개까지 취한다. */
    private List<RetrievedChunk> cap(List<Fused> ordered, int topK) {
        List<RetrievedChunk> selected = new ArrayList<>(topK);
        Map<String, Integer> perSource = new HashMap<>();
        for (Fused f : ordered) {
            String sourceKey = f.sourceType + ":" + f.sourceId;
            int used = perSource.getOrDefault(sourceKey, 0);
            if (used >= MAX_PER_SOURCE) {
                continue;
            }
            perSource.put(sourceKey, used + 1);
            selected.add(new RetrievedChunk(f.chunkId, f.sourceType, f.sourceId, f.chunkIndex, f.content,
                    f.similarity < 0 ? 0.0 : f.similarity));
            if (selected.size() >= topK) {
                break;
            }
        }
        return selected;
    }

    /** 후보 하나. 키워드로만 온 청크는 {@code similarity}가 -1로 남는다. */
    private static final class Fused {
        final Long chunkId;
        final SourceType sourceType;
        final Long sourceId;
        final Integer chunkIndex;
        final String content;
        double similarity = -1;
        double score;
        double rerankScore;

        static Fused of(NearestChunk c) {
            return new Fused(c.chunkId(), c.sourceType(), c.sourceId(), c.chunkIndex(), c.content());
        }

        Fused(Long chunkId, SourceType sourceType, Long sourceId, Integer chunkIndex, String content) {
            this.chunkId = chunkId;
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.chunkIndex = chunkIndex;
            this.content = content;
        }
    }

    /**
     * 키워드(LIKE) 폴백. 질의를 토큰으로 쪼개 길이 2 이상인 것들로 검색한다.
     *
     * <p>질의 문장 전체를 LIKE로 던지면 절대 매치되지 않으므로 토큰 단위로 나눈다.
     * 형태소 분석기가 없어 조사가 붙은 채로 검색되는 한계가 있다 — 폴백 품질이 낮은 이유 중 하나이며,
     * ADR-0003 3-1(ngram FULLTEXT)이 겨냥하는 지점이기도 하다.
     *
     * <p>순위는 맞은 키워드 수, 같으면 최신순이다. 키워드마다 최신순으로 자르면 흔한 단어 하나가
     * 후보를 다 채운다(#186).
     */
    private List<RetrievedChunk> keywordFallback(String query, String userId, boolean admin, int topK) {
        List<String> keywords = fallbackKeywords(query);
        if (keywords.isEmpty() || topK <= 0) {
            return List.of();
        }
        String model = embeddingClient.getModelName();
        List<KeywordChunk> found = admin
                ? keywordChunkJdbcRepository.findAll(model, keywords, topK)
                // 폴백도 벡터 경로와 같은 범위다(ADR-0006 7절) — 구역 승인분까지, 그 이상은 아니다.
                : keywordChunkJdbcRepository.findVisible(model, userId, areaOf(userId), keywords, topK);

        // 폴백에는 유사도 점수가 없다. 0.0으로 두어 벡터 결과와 혼동되지 않게 한다.
        return found.stream()
                .map(c -> new RetrievedChunk(c.chunkId(), c.sourceType(), c.sourceId(),
                        c.chunkIndex(), c.content(), 0.0))
                .toList();
    }

    /**
     * 폴백 키워드. 공백으로 쪼개 글자·숫자만 남기고, 2글자 이상을 중복 없이 긴 것부터 고른다.
     *
     * <p>글자·숫자만 남기므로 {@code LIKE}의 {@code %}·{@code _}가 키워드에 들어가지 않는다.
     */
    static List<String> fallbackKeywords(String query) {
        if (query == null) {
            return List.of();
        }
        return Arrays.stream(query.split("\\s+"))
                .map(t -> t.replaceAll("[^\\p{L}\\p{N}]", ""))
                .filter(t -> t.length() >= 2)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(MAX_FALLBACK_KEYWORDS)
                .toList();
    }

    /**
     * 조회자의 구역. 없는 사용자면 null — 구역 승인분을 하나도 고르지 않는다(좁히는 쪽으로 실패한다).
     * 앱의 작업일지 화면({@code WorkLogsService.requireVisible})과 같은 값을 본다.
     */
    private String areaOf(String userId) {
        return memberRepository.findByUserId(userId)
                .map(Member::getShipYardArea)
                .orElse(null);
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
