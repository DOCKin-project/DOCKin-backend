package com.DOCKin.rag.repository;

import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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
     * 원본이 지워질 때 그 원본의 청크 전부(모델 불문). FK가 없으니 원본을 지우는 쪽이 같은 트랜잭션에서 부른다(#101).
     * 벌크 JPQL — 청크를 엔티티로 올려 하나씩 지울 이유가 없다. {@code clearAutomatically}는 같은 영속성 컨텍스트에
     * 그 청크가 올라와 있을 일이 없어 안 건다.
     */
    @Modifying
    @Query("DELETE FROM DocumentChunk c WHERE c.sourceType = :sourceType AND c.sourceId IN :sourceIds")
    int deleteBySource(@Param("sourceType") SourceType sourceType, @Param("sourceIds") Collection<Long> sourceIds);

    /*
     * 최근접 검색(pgvector <=>)은 여기 없다. 엔티티를 다루지 않는 순수 SQL이라
     * NearestChunkJdbcRepository가 JdbcClient로 친다. 이 인터페이스에는 엔티티를 오가는
     * 파생 쿼리와 JPQL만 둔다.
     */

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
