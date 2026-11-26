package com.DOCKin.rag.repository;

import com.DOCKin.rag.model.SourceType;

/**
 * 키워드 폴백 결과 한 건. {@code matched}는 질의 키워드 중 이 청크 본문에 들어 있는 개수다.
 */
public record KeywordChunk(Long chunkId,
                           SourceType sourceType,
                           Long sourceId,
                           Integer chunkIndex,
                           String content,
                           int matched) {
}
