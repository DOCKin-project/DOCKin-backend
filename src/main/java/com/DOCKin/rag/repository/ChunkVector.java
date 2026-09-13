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
 *
 * <p><b>이 투영 자체가 2b에서 사라질 대상이다.</b> 유사도 계산이 DB로 넘어가면 후보 벡터를
 * 애플리케이션으로 가져오는 단계가 없어지고, 곧 top-k만 받으면 된다.
 * 지금은 타입만 {@code vector}로 바뀐 상태이고 계산 위치는 아직 애플리케이션이다.
 *
 * <p>{@code embeddingDim}을 더 이상 싣지 않는다 — 벡터가 {@code float[]}로 오므로
 * 길이가 곧 차원이고, 별도 컬럼을 읽는 것은 같은 사실을 두 번 가져오는 셈이다.
 */
public record ChunkVector(
        Long chunkId,
        SourceType sourceType,
        Long sourceId,
        float[] embedding
) {}
