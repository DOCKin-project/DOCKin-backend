package com.DOCKin.rag.repository;

import com.DOCKin.rag.model.SourceType;

/**
 * 유사도 계산에만 필요한 최소 필드 투영(projection).
 *
 * <p><b>{@code content}를 싣지 않는 것이 핵심이다.</b> 브루트포스는 후보 전체를 메모리에 올리는데,
 * 엔티티를 통째로 읽으면 청크당 벡터 1.5KB + 본문 약 1.5KB(한글 500자 UTF-8)가 되어
 * 10만 청크에서 300MB에 달한다. {@code Xmx400M}에서는 위험하다.
 *
 * <p>투영으로 벡터만 읽으면 약 절반으로 줄고, 본문은 최종 top-k에 대해서만 조회하면 된다.
 * 근거는 {@code docs/SERVICE-SCALE-ASSUMPTIONS.md} 3-2 참고.
 */
public record ChunkVector(
        Long chunkId,
        SourceType sourceType,
        Long sourceId,
        byte[] embedding,
        Integer embeddingDim
) {}
