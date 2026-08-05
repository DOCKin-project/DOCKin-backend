package com.DOCKin.rag.repository;

/**
 * 벡터 검색 결과 한 건. 네이티브 쿼리의 컬럼을 그대로 받는 인터페이스 투영이다.
 *
 * <h3>{@link ChunkVector}와의 차이 — 본문을 싣는다</h3>
 * {@code ChunkVector}는 <b>후보 전체</b>를 메모리에 올려야 했기에 본문을 뺐다.
 * 10만 청크에서 본문까지 실으면 300MB가 되어 {@code Xmx400M}에서 위험했기 때문이다.
 *
 * <p>유사도 계산이 DB로 넘어간 뒤에는 그 전제가 사라졌다. 애플리케이션에 오는 것은
 * 후보 전체가 아니라 <b>top-k 남짓</b>이므로 본문을 함께 받아도 수십 KB에 그친다.
 * 덕분에 "벡터만 조회 → 점수 계산 → 선택된 것만 본문 재조회"라는 2단계 왕복이
 * 한 번의 조회로 합쳐졌다.
 *
 * <h3>distance는 유사도가 아니다</h3>
 * pgvector의 {@code <=>}는 <b>코사인 거리</b>이며 {@code 1 - 코사인 유사도}다.
 * 작을수록 가깝다. 유사도로 쓰려면 {@code 1 - distance}로 뒤집어야 한다.
 */
public interface NearestChunk {

    Long getChunkId();

    /** {@code SourceType} enum의 이름. 네이티브 쿼리라 문자열로 온다. */
    String getSourceType();

    Long getSourceId();

    Integer getChunkIndex();

    String getContent();

    /** 코사인 <b>거리</b>(0에 가까울수록 유사). 유사도는 {@code 1 - distance}. */
    Double getDistance();
}
