package com.DOCKin.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 재순위 모델 서버(TEI {@code /rerank}) 클라이언트.
 *
 * <p>임베딩(bi-encoder)은 질문과 문서를 <b>따로</b> 벡터로 만들어 거리를 잰다. 빠르지만 둘을 함께 보지 못한다.
 * 재순위(cross-encoder)는 질문과 후보 문서를 <b>한 입력으로 같이</b> 읽어 관련도를 낸다. 느린 대신 정확하다.
 * 그래서 넓게 뽑은 후보 20개 남짓에만 쓴다(ADR-0013 2단계 4).
 *
 * <p><b>실패하면 예외 대신 빈 값을 돌려준다.</b> 재순위는 정상 경로의 보강이다 — 서버가 없거나 느리면
 * 재순위 전 순서로 답한다. 임베딩 서버 장애가 키워드 폴백으로 떨어지는 것과 같은 원칙이다.
 */
@Slf4j
@Component
public class RerankClient {

    private final WebClient rerankWebClient;
    private final Duration timeout;

    public RerankClient(@Qualifier("rerankWebClient") WebClient rerankWebClient,
                        @org.springframework.beans.factory.annotation.Value(
                                "${external-api.reranker.response-timeout-ms:5000}") long timeoutMs) {
        this.rerankWebClient = rerankWebClient;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /**
     * 각 문서의 관련도 점수를 <b>입력 순서대로</b> 돌려준다. 실패하면 빈 값.
     *
     * <p>TEI는 점수 내림차순으로 {@code [{index, score}]}를 준다. 여기서 입력 순서로 되돌린다.
     * {@code raw_scores=false}면 시그모이드를 거친 0~1 값이다 — 근거 없음 임계값을 걸 때 해석하기 쉽다.
     */
    public Optional<double[]> score(String query, List<String> texts) {
        if (texts.isEmpty()) {
            return Optional.of(new double[0]);
        }
        try {
            RankedText[] ranked = rerankWebClient.post()
                    .uri("/rerank")
                    .bodyValue(new RerankRequest(query, texts, false, true))
                    .retrieve()
                    .bodyToMono(RankedText[].class)
                    .block(timeout);
            if (ranked == null || ranked.length != texts.size()) {
                log.warn("[RAG] 재순위 응답 개수 불일치: 요청 {}건, 응답 {}건 - 재순위 없이 진행합니다.",
                        texts.size(), ranked == null ? 0 : ranked.length);
                return Optional.empty();
            }
            double[] scores = new double[texts.size()];
            for (RankedText r : ranked) {
                if (r.index() < 0 || r.index() >= scores.length) {
                    log.warn("[RAG] 재순위 응답 index 범위 밖: {} - 재순위 없이 진행합니다.", r.index());
                    return Optional.empty();
                }
                scores[r.index()] = r.score();
            }
            return Optional.of(scores);
        } catch (Exception e) {
            // 타임아웃은 메시지가 null이라 타입을 같이 찍는다(EmbeddingClient와 같은 이유).
            log.warn("[RAG] 재순위 서버 호출 실패 [{}]: {} - 재순위 없이 진행합니다.",
                    e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    record RerankRequest(String query, List<String> texts, boolean raw_scores, boolean truncate) {}

    record RankedText(int index, double score) {}
}
