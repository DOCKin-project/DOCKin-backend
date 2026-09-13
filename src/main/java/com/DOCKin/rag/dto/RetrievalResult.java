package com.DOCKin.rag.dto;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 근거 검색 결과. <b>어떤 방식으로 찾았는지를 함께 들고 다닌다.</b>
 *
 * <p>폴백은 조용히 일어나면 안 된다. 임베딩 서버가 죽어 키워드 검색으로 떨어진 상태를
 * 기록해두지 않으면 "요즘 챗봇 답변이 이상하다"는 현상만 남고 원인을 추적할 수 없다.
 * 이 값은 {@code chat_history.retrieval_mode}에 저장된다.
 */
public record RetrievalResult(RetrievalMode mode, List<RetrievedChunk> chunks) {

    public enum RetrievalMode {
        /** 정상 경로 - 벡터 유사도 검색 */
        VECTOR,
        /** 폴백 - 임베딩 서버 장애로 키워드(LIKE) 검색 */
        KEYWORD,
        /** 근거를 찾지 못함. 챗봇은 근거 없이 답한다 */
        NONE
    }

    public static RetrievalResult none() {
        return new RetrievalResult(RetrievalMode.NONE, List.of());
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    /** 감사 추적용. {@code chat_history.source_chunk_ids}에 저장할 형태로 만든다. */
    public String toSourceIds() {
        if (chunks.isEmpty()) {
            return null;
        }
        return chunks.stream()
                .map(c -> String.valueOf(c.chunkId()))
                .collect(Collectors.joining(","));
    }
}
