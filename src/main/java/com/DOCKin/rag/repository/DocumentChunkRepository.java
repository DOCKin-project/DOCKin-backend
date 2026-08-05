package com.DOCKin.rag.repository;

import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    /**
     * 재색인 시 기존 청크 조회. {@code content_hash}를 비교해 변경분만 임베딩을 다시 만든다.
     * 배치가 중간에 끊겨도 이어서 돌릴 수 있게 하는 근거가 이 조회다.
     */
    List<DocumentChunk> findBySourceTypeAndSourceIdAndEmbeddingModel(
            SourceType sourceType, Long sourceId, String embeddingModel);

    /** 원본이 삭제되었거나 청크 수가 줄어든 경우 정리용. FK가 없으므로 애플리케이션이 책임진다. */
    void deleteBySourceTypeAndSourceIdAndEmbeddingModel(
            SourceType sourceType, Long sourceId, String embeddingModel);

    long countByEmbeddingModel(String embeddingModel);

    /**
     * <b>유사도 계산이 DB 안에서 일어난다.</b> 질의와 가까운 순으로 정렬해 상위 {@code limit}건만 반환한다.
     *
     * <h3>왜 네이티브 쿼리인가</h3>
     * JPQL에는 {@code <=>}(코사인 거리) 같은 pgvector 연산자가 없다. Hibernate의 벡터 함수
     * ({@code cosine_distance()})를 쓰는 방법도 있으나, <b>인덱스가 타는지를 {@code EXPLAIN}으로
     * 확인할 때 실제로 나가는 SQL이 그대로 보이는 편</b>이 낫다고 판단했다. 이 쿼리는 성능이 곧 존재 이유라
     * 방언 독립성보다 실행계획의 투명성이 우선이다.
     *
     * <h3>왜 이 방향이 근본 해결인가 — 실측</h3>
     * <pre>
     * 애플리케이션에서 계산 (벡터 전체를 JDBC로 전송)   4,059 ms
     * DB 안에서 계산 (인덱스 없이 정확 최근접)             약 57 ms   ← 약 70배
     * DB 안에서 계산 + HNSW 인덱스 (워밍)                  약 1.7 ms
     * </pre>
     * 인덱스를 만들기 전에 이미 70배다. 병목의 81%가 146MB를 애플리케이션으로 옮기는 전송이었기 때문이며,
     * <b>인덱스는 그 위에 얹히는 두 번째 층</b>이다. 상세는 ADR-0006 8-2.
     *
     * <h3>권한 선필터는 그대로다</h3>
     * 공개 청크만 대상으로 하는 이 쿼리와, 본인 소유만 대상으로 하는
     * {@link #findNearestOwned}를 각각 실행해 애플리케이션에서 합친다.
     * 후보에 애초에 들어오지 않으므로 "내가 못 보는 문서가 존재한다"는 사실도 새지 않는다(ADR-0006 6절).
     *
     * <p>둘로 나눈 것은 MySQL 시절 {@code OR}가 인덱스를 전혀 타지 못해서였다.
     * PostgreSQL은 BitmapOr로 처리하지만, 분리가 여전히 조금 빠르고 각 분기가 단일 조건이라
     * 실행계획이 단순해져 그대로 둔다.
     *
     * @param queryVector pgvector 텍스트 표현({@code "[0.1,-0.2,...]"}).
     *                    바인딩 파라미터는 타입을 모르므로 SQL에서 {@code CAST(... AS vector)}로 명시한다
     */
    @Query(value = """
            SELECT chunk_id      AS "chunkId",
                   source_type   AS "sourceType",
                   source_id     AS "sourceId",
                   chunk_index   AS "chunkIndex",
                   content       AS "content",
                   embedding <=> CAST(:queryVector AS vector) AS "distance"
            FROM document_chunks
            WHERE embedding_model = :model
              AND visibility = 'PUBLIC'
            ORDER BY embedding <=> CAST(:queryVector AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<NearestChunk> findNearestPublic(@Param("model") String embeddingModel,
                                         @Param("queryVector") String queryVector,
                                         @Param("limit") int limit);

    /** 본인이 작성한 청크만 대상으로 한다. {@link #findNearestPublic}과 짝을 이룬다. */
    @Query(value = """
            SELECT chunk_id      AS "chunkId",
                   source_type   AS "sourceType",
                   source_id     AS "sourceId",
                   chunk_index   AS "chunkIndex",
                   content       AS "content",
                   embedding <=> CAST(:queryVector AS vector) AS "distance"
            FROM document_chunks
            WHERE embedding_model = :model
              AND visibility = 'OWNER'
              AND owner_user_id = :userId
            ORDER BY embedding <=> CAST(:queryVector AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<NearestChunk> findNearestOwned(@Param("model") String embeddingModel,
                                        @Param("userId") String userId,
                                        @Param("queryVector") String queryVector,
                                        @Param("limit") int limit);

    /**
     * 관리자용. 권한 필터가 없다.
     *
     * <p>필터가 없어 HNSW 인덱스가 온전히 동작하는 유일한 경로다 —
     * 10만 청크에서 약 1.7ms. 필터가 붙는 위 두 경로는 인덱스 탐색 중 대부분이 걸러져
     * 이득이 크게 줄어든다(ADR-0006 8-2).
     */
    @Query(value = """
            SELECT chunk_id      AS "chunkId",
                   source_type   AS "sourceType",
                   source_id     AS "sourceId",
                   chunk_index   AS "chunkIndex",
                   content       AS "content",
                   embedding <=> CAST(:queryVector AS vector) AS "distance"
            FROM document_chunks
            WHERE embedding_model = :model
            ORDER BY embedding <=> CAST(:queryVector AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<NearestChunk> findNearestAll(@Param("model") String embeddingModel,
                                      @Param("queryVector") String queryVector,
                                      @Param("limit") int limit);

    /**
     * 벡터를 {@code float[]}로 되살려 읽는 투영. <b>검색 경로에서는 더 이상 쓰지 않는다.</b>
     *
     * <p>유사도 계산이 DB로 넘어가면서 후보 벡터를 애플리케이션으로 가져올 이유가 사라졌다.
     * 그럼에도 남겨둔 것은 {@code VectorTypeMappingTest}가 <b>생성자 투영에서도 {@code vector}가
     * {@code float[]}로 되살아나는지</b>를 검증하기 때문이다 — 엔티티 왕복과는 다른 경로이므로
     * 따로 확인할 값어치가 있다.
     */
    @Query("""
            SELECT new com.DOCKin.rag.repository.ChunkVector(
                       c.chunkId, c.sourceType, c.sourceId, c.embedding)
            FROM DocumentChunk c
            WHERE c.embeddingModel = :model
            """)
    List<ChunkVector> findAllSearchTargets(@Param("model") String embeddingModel);

    /**
     * 임베딩 서버 장애 시 사용하는 키워드 폴백.
     *
     * <p><b>같은 테이블에 LIKE를 거는 이유는 권한 모델을 재사용하기 위해서다.</b>
     * 기존 {@code WorkLogRepository.searchWorkLogs}는 소유자 필터가 없어 그대로 폴백에 쓰면
     * 남의 작업일지가 챗봇 근거로 새어 나간다. 벡터 검색과 폴백이 동일한 선필터를 공유해야
     * 장애 상황에서만 권한이 느슨해지는 사고를 막을 수 있다.
     *
     * <p>양쪽 와일드카드 LIKE라 인덱스를 타지 못한다(ADR-0002 2-2와 동일한 한계).
     * 장애 시에만 도는 경로이고 {@code Pageable}로 건수를 제한하므로 Phase 1에서는 감수한다.
     * ADR-0003 3-1의 ngram FULLTEXT를 도입하면 이 쿼리가 그 대상이 된다.
     */
    @Query("""
            SELECT c FROM DocumentChunk c
            WHERE c.embeddingModel = :model
              AND (c.visibility = com.DOCKin.rag.model.Visibility.PUBLIC
                   OR c.ownerUserId = :userId)
              AND c.content LIKE %:keyword%
            ORDER BY c.chunkId DESC
            """)
    List<DocumentChunk> searchByKeyword(@Param("model") String embeddingModel,
                                        @Param("userId") String userId,
                                        @Param("keyword") String keyword,
                                        Pageable pageable);

    /** 관리자용 키워드 폴백. 권한 필터 없이 전체를 대상으로 한다. */
    @Query("""
            SELECT c FROM DocumentChunk c
            WHERE c.embeddingModel = :model
              AND c.content LIKE %:keyword%
            ORDER BY c.chunkId DESC
            """)
    List<DocumentChunk> searchAllByKeyword(@Param("model") String embeddingModel,
                                           @Param("keyword") String keyword,
                                           Pageable pageable);
}
