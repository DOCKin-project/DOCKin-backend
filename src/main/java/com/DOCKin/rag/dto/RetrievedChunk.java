package com.DOCKin.rag.dto;

import com.DOCKin.rag.model.SourceType;

/**
 * 검색으로 선택된 근거 청크. 챗봇 프롬프트에 주입되고, 응답의 출처로 기록된다.
 *
 * @param score 코사인 유사도(-1 ~ 1). 값이 클수록 질의와 가깝다
 */
public record RetrievedChunk(
        Long chunkId,
        SourceType sourceType,
        Long sourceId,
        Integer chunkIndex,
        String content,
        double score
) {}
