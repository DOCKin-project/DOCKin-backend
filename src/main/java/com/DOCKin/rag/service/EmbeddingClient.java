package com.DOCKin.rag.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 임베딩 추론 서버(TEI) 호출 클라이언트.
 *
 * <h3>e5 프리픽스 규칙</h3>
 * {@code multilingual-e5-*} 계열은 학습 시 입력 앞에 역할 프리픽스를 붙여 학습했다.
 * 검색 질의는 {@code "query: "}, 색인 대상 문서는 {@code "passage: "}를 붙여야 한다.
 * <b>이 규칙을 어겨도 에러가 나지 않고 검색 품질만 조용히 떨어지므로</b> 원인 추적이 어렵다.
 * 그래서 호출자가 프리픽스를 직접 다루지 않도록 메서드를 질의용/문서용으로 나눠 강제한다.
 *
 * <h3>배치</h3>
 * 실측상 배치 호출이 단건 대비 약 8배 빠르다(12ms/건 vs 99ms/건, i3-6100 CPU 추론).
 * 인덱싱은 반드시 {@link #embedPassages(List)}로 묶어서 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    private static final String QUERY_PREFIX = "query: ";
    private static final String PASSAGE_PREFIX = "passage: ";

    /** CPU 추론이라 배치 한 건이 수백 ms 걸릴 수 있어 넉넉히 잡는다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final WebClient embeddingWebClient;

    @Value("${rag.embedding.model}")
    private String modelName;

    @Value("${rag.embedding.batch-size}")
    private int batchSize;

    public String getModelName() {
        return modelName;
    }

    /**
     * 검색 질의를 벡터로 변환한다. {@code "query: "} 프리픽스가 자동으로 붙는다.
     *
     * @throws BusinessException 임베딩 서버 호출 실패 시. 호출자는 이를 잡아 LIKE 검색으로 폴백한다
     */
    public float[] embedQuery(String text) {
        List<float[]> result = call(List.of(QUERY_PREFIX + text));
        return result.get(0);
    }

    /**
     * 색인 대상 문서들을 벡터로 변환한다. {@code "passage: "} 프리픽스가 자동으로 붙는다.
     *
     * <p>입력이 배치 크기를 넘으면 나눠서 순차 호출한다. 임베딩 서버가 CPU 2코어라
     * 클라이언트에서 병렬로 던져도 서버에서 직렬화되므로 순차가 맞다.
     *
     * @return 입력과 같은 순서, 같은 크기의 벡터 목록
     */
    public List<float[]> embedPassages(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> all = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> slice = texts.subList(i, Math.min(i + batchSize, texts.size()))
                    .stream()
                    .map(t -> PASSAGE_PREFIX + t)
                    .toList();
            all.addAll(call(slice));
        }
        return all;
    }

    private List<float[]> call(List<String> inputs) {
        try {
            float[][] response = embeddingWebClient.post()
                    .uri("/embed")
                    .bodyValue(new EmbedRequest(inputs))
                    .retrieve()
                    .onStatus(status -> status.isError(), r ->
                            r.bodyToMono(String.class).flatMap(body -> {
                                log.error("임베딩 서버 오류 응답: {}", body);
                                return Mono.error(new BusinessException(ErrorCode.EMBEDDING_SERVER_ERROR));
                            }))
                    .bodyToMono(float[][].class)
                    .block(TIMEOUT);

            if (response == null || response.length != inputs.size()) {
                log.error("임베딩 응답 개수 불일치: 요청 {}건, 응답 {}건",
                        inputs.size(), response == null ? 0 : response.length);
                throw new BusinessException(ErrorCode.EMBEDDING_SERVER_ERROR);
            }
            return List.of(response);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 서버가 내려가 있는 경우도 여기로 온다. 호출자가 폴백할 수 있도록 도메인 예외로 감싼다.
            log.error("임베딩 서버 호출 실패: {}", e.getMessage());
            throw new BusinessException(ErrorCode.EMBEDDING_SERVER_ERROR);
        }
    }

    /**
     * float 배열을 리틀엔디언 float32 바이트로 눕힌다.
     *
     * <p><b>더 이상 운영 경로에서 쓰지 않는다.</b> {@code document_chunks.embedding}이
     * MySQL {@code VARBINARY} → PostgreSQL {@code BYTEA}였을 때 저장 형식을 맞추던 변환이며,
     * pgvector {@code vector} 매핑 이후에는 {@code float[]}를 그대로 저장한다.
     *
     * <p>남겨두는 이유는 {@code BruteForceSearchBenchmarkTest}가 <b>Phase 1 기준선을
     * 그대로 재현</b>하기 때문이다. 그 벤치마크는 BYTEA 테이블을 직접 만들어 재는 기록이고,
     * ANN 도입 후 지연시간·recall을 비교할 때의 대조군이다. 벤치마크를 걷어낼 때 함께 사라질 자리다.
     */
    public static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    /** {@link #toBytes(float[])}의 역변환. 같은 이유로 벤치마크 전용이다. */
    public static float[] toFloats(byte[] bytes) {
        if (bytes.length % Float.BYTES != 0) {
            throw new BusinessException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    /** TEI {@code /embed} 요청 본문. {@code inputs}는 문자열 또는 문자열 배열을 받는다. */
    private record EmbedRequest(List<String> inputs) {}
}
