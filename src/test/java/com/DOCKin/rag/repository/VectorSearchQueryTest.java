package com.DOCKin.rag.repository;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import com.DOCKin.rag.model.Visibility;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검증: 유사도 계산을 DB로 옮긴 {@link NearestChunkJdbcRepository}의 SQL이 실제로 동작하는가?
 *
 * <h3>단위 테스트로는 확인할 수 없는 것들</h3>
 * {@code RetrievalServiceTest}는 리포지토리를 목킹하므로 <b>SQL이 한 줄도 실행되지 않는다.</b>
 * 다음은 실제 PostgreSQL에 던져봐야만 드러난다.
 *
 * <ul>
 *   <li>{@code CAST(:queryVector AS vector)} 바인딩이 통하는가 — 문자열로 넘긴 벡터가 타입 변환되는가</li>
 *   <li>{@code LIMIT :limit} 파라미터 바인딩이 되는가</li>
 *   <li>RowMapper가 컬럼과 맞물리는가 ({@code source_type} 문자열이 enum으로 되살아나는가)</li>
 *   <li>JPA로 저장하고 flush만 한 행을 같은 트랜잭션의 JdbcClient가 보는가 (커넥션이 하나인가)</li>
 *   <li>{@code <=>}가 주는 값이 정말 코사인 거리인가 (= 1 - 코사인 유사도)</li>
 *   <li>권한 선필터가 실제로 남의 청크를 후보에서 빼는가</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NearestChunkJdbcRepository.class)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=update"
})
class VectorSearchQueryTest extends ContainerTestSupport {

    private static final String MODEL = "vector-search-test";
    private static final String OWNER = "user-a";
    private static final String OTHER = "user-b";

    /** 질의 벡터. 0번 축을 가리킨다. */
    private static final float[] QUERY = axis(0);

    /** 저장은 JPA로. */
    @Autowired
    private DocumentChunkRepository repository;

    /** 검색은 SQL로. 검증 대상이다. */
    @Autowired
    private NearestChunkJdbcRepository search;

    @Autowired
    private EntityManager entityManager;

    private String queryLiteral;

    @BeforeEach
    void setUp() {
        queryLiteral = literal(QUERY);
    }

    @Test
    @DisplayName("거리 오름차순으로 반환되고 distance는 1 - 코사인 유사도다")
    void 거리_정렬과_값() {
        // 질의와 같은 방향(거리 0), 45도(거리 약 0.293), 직교(거리 1)
        save(1L, axis(0), Visibility.PUBLIC, null);
        save(2L, diagonal(), Visibility.PUBLIC, null);
        save(3L, axis(1), Visibility.PUBLIC, null);
        entityManager.flush();

        List<NearestChunk> found = search.findNearestAll(MODEL, queryLiteral, 10);

        assertEquals(3, found.size());
        assertEquals(1L, found.get(0).sourceId());
        assertEquals(2L, found.get(1).sourceId());
        assertEquals(3L, found.get(2).sourceId());

        assertEquals(0.0, found.get(0).distance(), 1e-6);
        // 1 - cos(45도) = 1 - 0.7071
        assertEquals(1 - Math.sqrt(0.5), found.get(1).distance(), 1e-5);
        assertEquals(1.0, found.get(2).distance(), 1e-6);
    }

    @Test
    @DisplayName("본문과 메타데이터가 같은 쿼리에서 함께 온다 - 재조회 왕복이 없다")
    void 본문_동시_조회() {
        save(1L, axis(0), Visibility.PUBLIC, null);
        entityManager.flush();

        NearestChunk chunk = search.findNearestAll(MODEL, queryLiteral, 1).get(0);

        assertEquals("청크 본문 1", chunk.content());
        assertEquals(SourceType.WORK_LOG, chunk.sourceType());
        assertEquals(0, chunk.chunkIndex());
    }

    @Test
    @DisplayName("LIMIT 파라미터가 바인딩된다")
    void limit_바인딩() {
        save(1L, axis(0), Visibility.PUBLIC, null);
        save(2L, axis(1), Visibility.PUBLIC, null);
        save(3L, axis(2), Visibility.PUBLIC, null);
        entityManager.flush();

        assertEquals(2, search.findNearestAll(MODEL, queryLiteral, 2).size());
    }

    @Test
    @DisplayName("권한 선필터 - 남이 소유한 청크는 후보에 들어오지 않는다")
    void 권한_선필터() {
        save(1L, axis(0), Visibility.PUBLIC, null);        // 누구나
        save(2L, axis(0), Visibility.OWNER, OWNER);        // 본인 것
        save(3L, axis(0), Visibility.OWNER, OTHER);        // 남의 것 - 가장 가깝지만 보이면 안 된다
        entityManager.flush();

        List<Long> visible = search.findNearestPublic(MODEL, queryLiteral, 10).stream()
                .map(NearestChunk::sourceId).toList();
        List<Long> owned = search.findNearestOwned(MODEL, OWNER, queryLiteral, 10).stream()
                .map(NearestChunk::sourceId).toList();

        assertEquals(List.of(1L), visible);
        assertEquals(List.of(2L), owned);
        assertFalse(visible.contains(3L), "남의 청크가 공개 후보에 섞이면 챗봇 근거로 새어 나간다");
        assertFalse(owned.contains(3L), "남의 청크가 소유 후보에 섞이면 안 된다");

        // 관리자 경로는 전부 본다.
        assertEquals(3, search.findNearestAll(MODEL, queryLiteral, 10).size());
    }

    @Test
    @DisplayName("다른 임베딩 모델로 색인된 청크는 섞이지 않는다")
    void 모델_필터() {
        save(1L, axis(0), Visibility.PUBLIC, null);
        DocumentChunk otherModel = DocumentChunk.builder()
                .sourceType(SourceType.WORK_LOG).sourceId(99L).chunkIndex(0)
                .languageCode("ko").content("다른 모델 청크")
                .contentHash(String.format("%064d", 99))
                .embedding(axis(0)).embeddingDim(DocumentChunk.EMBEDDING_DIM)
                .embeddingModel("some-other-model").visibility(Visibility.PUBLIC)
                .build();
        repository.save(otherModel);
        entityManager.flush();

        List<NearestChunk> found = search.findNearestAll(MODEL, queryLiteral, 10);

        assertEquals(1, found.size());
        assertTrue(found.stream().noneMatch(c -> c.sourceId() == 99L));
    }

    // --- helpers ---

    private void save(long sourceId, float[] embedding, Visibility visibility, String ownerUserId) {
        repository.save(DocumentChunk.builder()
                .sourceType(SourceType.WORK_LOG)
                .sourceId(sourceId)
                .chunkIndex(0)
                .languageCode("ko")
                .content("청크 본문 " + sourceId)
                .contentHash(String.format("%064d", sourceId))
                .embedding(embedding)
                .embeddingDim(DocumentChunk.EMBEDDING_DIM)
                .embeddingModel(MODEL)
                .visibility(visibility)
                .ownerUserId(ownerUserId)
                .build());
    }

    /** i번 축을 가리키는 단위 벡터. 서로 직교하므로 코사인 거리가 정확히 1이 된다. */
    private static float[] axis(int i) {
        float[] v = new float[DocumentChunk.EMBEDDING_DIM];
        v[i] = 1f;
        return v;
    }

    /** 0번과 1번 축의 45도 방향. 질의와의 코사인 유사도가 1/sqrt(2)다. */
    private static float[] diagonal() {
        float[] v = new float[DocumentChunk.EMBEDDING_DIM];
        v[0] = 1f;
        v[1] = 1f;
        return v;
    }

    private static String literal(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
