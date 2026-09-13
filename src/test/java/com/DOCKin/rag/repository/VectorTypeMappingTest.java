package com.DOCKin.rag.repository;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import com.DOCKin.rag.model.Visibility;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * 검증: {@code DocumentChunk.embedding}이 pgvector의 {@code vector(384)}로 왕복하는가?
 *
 * <h3>왜 필요한가</h3>
 * ADR-0006 8절과 백로그는 이 매핑을 "Hibernate가 모르는 타입 -- 커스텀 UserType 또는 네이티브 쿼리"로
 * 적어뒀으나, Hibernate 6.4부터 {@code hibernate-vector} 모듈이 {@link org.hibernate.type.SqlTypes#VECTOR}를
 * 제공한다. 애노테이션만으로 매핑되므로 <b>정말 그런지를 실제 DB에 대고 확인하는 것</b>이 이 테스트다.
 *
 * <h3>무엇을 보는가</h3>
 * 쓰기보다 <b>읽기가 위험하다.</b> 검색 경로는 엔티티가 아니라 {@link ChunkVector} 생성자 투영으로
 * 벡터만 뽑아 오는데(본문을 메모리에 올리지 않기 위한 설계), 투영에서도 {@code vector}가
 * {@code float[]}로 되살아나는지는 별개 문제다. 그래서 엔티티 왕복과 투영 왕복을 모두 본다.
 *
 * <h3>DB는 항상 있다</h3>
 * {@link ContainerTestSupport}가 pgvector 컨테이너를 띄우므로 건너뛰는 경로가 없다.
 * 이전에는 {@code @EnabledIfEnvironmentVariable}로 컨텍스트 로딩 자체를 막았는데,
 * 그 결과 <b>CI에서 이 검증이 한 번도 돌지 않았다</b>. 지금은 매 실행마다 돈다.
 *
 * <p>공식 {@code postgres} 이미지가 아니라 {@code pgvector/pgvector}를 쓰는 것이 여기서 중요하다 --
 * {@code vector} 확장이 없으면 이 테스트가 검증하려는 타입 자체가 존재하지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=update"
})
class VectorTypeMappingTest extends ContainerTestSupport {

    private static final String MODEL = "vector-mapping-test";

    @Autowired
    private DocumentChunkRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("float[]로 저장한 벡터가 값 손실 없이 그대로 돌아온다")
    void 엔티티_왕복() {
        float[] original = sampleVector();

        DocumentChunk saved = repository.save(chunk(1L, original, Visibility.PUBLIC, null));
        entityManager.flush();
        entityManager.clear(); // 1차 캐시를 비워야 DB에서 다시 읽는다

        DocumentChunk found = repository.findById(saved.getChunkId()).orElseThrow();

        // float32로 저장되므로 반올림 오차가 없어야 한다(double이었다면 달랐다).
        assertArrayEquals(original, found.getEmbedding());
        assertEquals(DocumentChunk.EMBEDDING_DIM, found.getEmbedding().length);
    }

    @Test
    @DisplayName("ChunkVector 투영에서도 vector가 float[]로 되살아난다")
    void 투영_왕복() {
        float[] original = sampleVector();
        repository.save(chunk(2L, original, Visibility.PUBLIC, null));
        entityManager.flush();
        entityManager.clear();

        List<ChunkVector> targets = repository.findAllSearchTargets(MODEL);

        assertEquals(1, targets.size());
        assertArrayEquals(original, targets.get(0).embedding());
    }

    @Test
    @DisplayName("DB가 차원을 강제한다 - 384가 아닌 벡터는 저장되지 않는다")
    void 차원_불일치_거부() {
        DocumentChunk wrong = chunk(3L, new float[]{1f, 2f, 3f}, Visibility.PUBLIC, null);

        // vector(384) 컬럼이라 DB가 거부한다. BYTEA 시절에는 조용히 저장되어
        // 검색 시점에 애플리케이션이 걸러내야 했던 상황이 여기서 막힌다.
        repository.save(wrong);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, entityManager::flush);
    }

    private static float[] sampleVector() {
        float[] vector = new float[DocumentChunk.EMBEDDING_DIM];
        for (int i = 0; i < vector.length; i++) {
            // 부호·소수를 섞어 바이트 순서나 정밀도 문제가 있으면 드러나게 한다.
            vector[i] = (i % 2 == 0 ? 1 : -1) * (i / 1000f);
        }
        return vector;
    }

    private static DocumentChunk chunk(long sourceId, float[] embedding,
                                       Visibility visibility, String ownerUserId) {
        return DocumentChunk.builder()
                .sourceType(SourceType.WORK_LOG)
                .sourceId(sourceId)
                .chunkIndex(0)
                .languageCode("ko")
                .content("벡터 매핑 검증용 청크")
                .contentHash(String.format("%064d", sourceId))
                .embedding(embedding)
                .embeddingDim(embedding.length)
                .embeddingModel(MODEL)
                .visibility(visibility)
                .ownerUserId(ownerUserId)
                .build();
    }
}
