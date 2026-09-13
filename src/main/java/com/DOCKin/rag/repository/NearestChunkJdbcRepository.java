package com.DOCKin.rag.repository;

import com.DOCKin.rag.model.SourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * pgvector 최근접 검색. <b>SQL을 SQL로 친다.</b>
 *
 * <h3>왜 JPA 리포지토리가 아닌가</h3>
 * 이 조회는 엔티티를 다루지 않는다. {@code <=>}(코사인 거리) 연산자는 JPQL에 없고,
 * 결과도 관리 대상 엔티티가 아니라 상위 몇 건의 평평한 행이다. 그런 것을
 * {@code @Query(nativeQuery = true)}로 {@code JpaRepository} 안에 두면 영속성 컨텍스트가
 * 아무 일도 하지 않으면서 이름만 빌려주는 꼴이 된다. JPA가 할 일은 JPA 리포지토리에,
 * SQL이 할 일은 여기에 둔다 — {@code DocumentChunkRepository}에는 엔티티를 오가는
 * 파생 쿼리와 JPQL만 남는다.
 *
 * <p>Hibernate의 벡터 함수({@code cosine_distance()})를 쓰는 방법도 있으나, <b>인덱스가 타는지를
 * {@code EXPLAIN}으로 확인할 때 실제로 나가는 SQL이 그대로 보이는 편</b>이 낫다.
 * 이 쿼리는 성능이 곧 존재 이유라 방언 독립성보다 실행계획의 투명성이 우선이다.
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
 * <h3>권한 선필터는 SQL 안에 있다</h3>
 * 공개 청크만 대상으로 하는 {@link #findNearestPublic}과 본인 소유만 대상으로 하는
 * {@link #findNearestOwned}를 각각 실행해 애플리케이션에서 합친다.
 * 후보에 애초에 들어오지 않으므로 "내가 못 보는 문서가 존재한다"는 사실도 새지 않는다(ADR-0006 6절).
 *
 * <p>둘로 나눈 것은 MySQL 시절 {@code OR}가 인덱스를 전혀 타지 못해서였다.
 * PostgreSQL은 BitmapOr로 처리하지만, 분리가 여전히 조금 빠르고 각 분기가 단일 조건이라
 * 실행계획이 단순해져 그대로 둔다.
 *
 * <h3>트랜잭션</h3>
 * {@code JdbcClient}는 스프링이 관리하는 커넥션을 쓰므로 호출자의 {@code @Transactional}
 * 안에서 JPA와 <b>같은 커넥션</b>을 탄다. 같은 트랜잭션에서 flush된 엔티티를 이 조회가 본다.
 */
@Repository
@RequiredArgsConstructor
public class NearestChunkJdbcRepository {

    /**
     * 세 조회가 공유하는 SELECT 목록. 필터만 다르고 결과 모양은 같다.
     *
     * <p>바인딩 파라미터는 타입을 모르므로 {@code CAST(:queryVector AS vector)}로 명시한다.
     * 거리 계산이 SELECT와 ORDER BY에 두 번 나오지만 PostgreSQL이 같은 식으로 인식해 한 번만 계산한다.
     */
    private static final String SELECT = """
            SELECT chunk_id, source_type, source_id, chunk_index, content,
                   embedding <=> CAST(:queryVector AS vector) AS distance
            FROM document_chunks
            WHERE embedding_model = :model
            """;

    private static final String ORDER_LIMIT = """
            ORDER BY embedding <=> CAST(:queryVector AS vector)
            LIMIT :limit
            """;

    private static final RowMapper<NearestChunk> ROW = (rs, i) -> new NearestChunk(
            rs.getLong("chunk_id"),
            SourceType.valueOf(rs.getString("source_type")),
            rs.getLong("source_id"),
            rs.getInt("chunk_index"),
            rs.getString("content"),
            rs.getDouble("distance"));

    private final JdbcClient jdbcClient;

    /**
     * 공개 청크 중 질의와 가까운 순으로 상위 {@code limit}건.
     *
     * @param queryVector pgvector 텍스트 표현({@code "[0.1,-0.2,...]"})
     */
    public List<NearestChunk> findNearestPublic(String embeddingModel, String queryVector, int limit) {
        return jdbcClient.sql(SELECT + "  AND visibility = 'PUBLIC'\n" + ORDER_LIMIT)
                .param("model", embeddingModel)
                .param("queryVector", queryVector)
                .param("limit", limit)
                .query(ROW)
                .list();
    }

    /** 본인이 작성한 청크만 대상으로 한다. {@link #findNearestPublic}과 짝을 이룬다. */
    public List<NearestChunk> findNearestOwned(String embeddingModel, String userId, String queryVector, int limit) {
        return jdbcClient.sql(SELECT + "  AND visibility = 'OWNER'\n  AND owner_user_id = :userId\n" + ORDER_LIMIT)
                .param("model", embeddingModel)
                .param("userId", userId)
                .param("queryVector", queryVector)
                .param("limit", limit)
                .query(ROW)
                .list();
    }

    /**
     * 관리자용. 권한 필터가 없다.
     *
     * <p>필터가 없어 HNSW 인덱스가 온전히 동작하는 유일한 경로다 —
     * 10만 청크에서 약 1.7ms. 필터가 붙는 위 두 경로는 인덱스 탐색 중 대부분이 걸러져
     * 이득이 크게 줄어든다(ADR-0006 8-2).
     */
    public List<NearestChunk> findNearestAll(String embeddingModel, String queryVector, int limit) {
        return jdbcClient.sql(SELECT + ORDER_LIMIT)
                .param("model", embeddingModel)
                .param("queryVector", queryVector)
                .param("limit", limit)
                .query(ROW)
                .list();
    }
}
