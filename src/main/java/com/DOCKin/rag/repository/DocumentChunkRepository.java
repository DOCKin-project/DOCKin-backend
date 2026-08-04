package com.DOCKin.rag.repository;

import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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
     * 일반 사용자용 브루트포스 검색 대상 조회. <b>권한 선필터가 SQL에서 적용된다.</b>
     *
     * <p>전체 공개 청크(PUBLIC)와 본인이 작성한 청크(OWNER + 본인)만 후보로 삼는다.
     * 벡터 유사도만으로는 접근 제어를 할 수 없으므로, 남의 작업일지가 챗봇 근거로
     * 새어 나가는 것을 막는 방어선이 여기다.
     *
     * <p><b>후필터가 아니라 선필터인 이유:</b> top-k를 먼저 뽑고 나서 권한으로 거르면
     * 요청한 k보다 적게 반환되어 "내가 못 보는 문서가 존재한다"는 사실이 유출된다.
     * 검색어를 바꿔가며 반복하면 내용을 못 봐도 문서의 존재 윤곽을 그릴 수 있다.
     * 선필터는 후보에 아예 없으므로 존재 여부도 새지 않는다.
     *
     * <p>Phase 1은 ANN 인덱스 없이 전체 후보를 훑는다(정확 최근접). 결과가 메모리에 올라가므로
     * {@code Xmx400M} 기준 약 10만 청크가 상한이며, 초과 시 Phase 2(ANN)로 전환한다.
     */
    /**
     * 권한 선필터를 적용한 검색 후보. 공개 청크와 본인 소유 청크를 각각 조회해 합친다.
     *
     * <h3>왜 하나의 OR 쿼리가 아닌가 — {@code EXPLAIN ANALYZE} 실측 근거</h3>
     * 원래는 {@code WHERE visibility = 'PUBLIC' OR ownerUserId = :userId} 한 방이었으나,
     * 10만 행에서 실행계획을 찍어보니 <b>{@code idx_chunk_visibility}를 전혀 타지 않았다.</b>
     * OR로 묶인 두 조건이 서로 다른 컬럼을 보기 때문에 단일 인덱스로 처리하지 못하고,
     * {@code idx_chunk_model}로 10만 행을 모두 읽은 뒤 Filter에서 10,020건으로 줄이고 있었다(약 90%를 헛읽음).
     *
     * <pre>
     * [변경 전] Filter: (visibility='PUBLIC' or owner_user_id='user1')   rows=10020, 770ms
     *              -> Index lookup using idx_chunk_model                  rows=100000   ← 전부 읽음
     *
     * [변경 후] 두 조회 모두 idx_chunk_visibility 사용                                    659ms
     *              visibility='PUBLIC'                        rows=10000
     *              visibility='OWNER' AND owner_user_id=?      rows=20, 1.19ms
     * </pre>
     *
     * <p>JPQL은 {@code UNION ALL}을 안정적으로 지원하지 않아 두 쿼리로 나누고 여기서 합친다.
     * DB가 수행하는 인덱스 조회는 동일하며, 네이티브 SQL을 쓰지 않아 방언 독립성도 유지된다.
     *
     * <p><b>개선폭은 약 14%에 그친다(770 → 659ms).</b> 공개 청크 10,000건은 어차피 읽어야 하고,
     * 그 벡터를 옮기는 비용은 인덱스를 타든 안 타든 같기 때문이다.
     * 근본 해결은 유사도 계산을 DB 안으로 옮기는 것이다 — 상세는 {@code SERVICE-SCALE-ASSUMPTIONS.md} 6-3.
     */
    default List<ChunkVector> findSearchTargets(String embeddingModel, String userId) {
        List<ChunkVector> targets = new ArrayList<>(findPublicSearchTargets(embeddingModel));
        targets.addAll(findOwnedSearchTargets(embeddingModel, userId));
        return targets;
    }

    @Query("""
            SELECT new com.DOCKin.rag.repository.ChunkVector(
                       c.chunkId, c.sourceType, c.sourceId, c.embedding, c.embeddingDim)
            FROM DocumentChunk c
            WHERE c.embeddingModel = :model
              AND c.visibility = com.DOCKin.rag.model.Visibility.PUBLIC
            """)
    List<ChunkVector> findPublicSearchTargets(@Param("model") String embeddingModel);

    @Query("""
            SELECT new com.DOCKin.rag.repository.ChunkVector(
                       c.chunkId, c.sourceType, c.sourceId, c.embedding, c.embeddingDim)
            FROM DocumentChunk c
            WHERE c.embeddingModel = :model
              AND c.visibility = com.DOCKin.rag.model.Visibility.OWNER
              AND c.ownerUserId = :userId
            """)
    List<ChunkVector> findOwnedSearchTargets(@Param("model") String embeddingModel,
                                             @Param("userId") String userId);

    /** 관리자용. 권한 필터 없이 전체를 대상으로 한다. */
    @Query("""
            SELECT new com.DOCKin.rag.repository.ChunkVector(
                       c.chunkId, c.sourceType, c.sourceId, c.embedding, c.embeddingDim)
            FROM DocumentChunk c
            WHERE c.embeddingModel = :model
            """)
    List<ChunkVector> findAllSearchTargets(@Param("model") String embeddingModel);

    /** 최종 top-k에 대해서만 본문을 조회한다. 후보 전체의 본문을 메모리에 올리지 않기 위함이다. */
    List<DocumentChunk> findByChunkIdIn(List<Long> chunkIds);

    /**
     * 임베딩 서버 장애 시 사용하는 키워드 폴백.
     *
     * <p><b>같은 테이블에 LIKE를 거는 이유는 권한 모델을 재사용하기 위해서다.</b>
     * 기존 {@code Work_logsRepository.searchWorkLogs}는 소유자 필터가 없어 그대로 폴백에 쓰면
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
