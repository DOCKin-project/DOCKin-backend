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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검증: 키워드 폴백 SQL({@link KeywordChunkJdbcRepository})이 실제 PostgreSQL에서
 * 맞은 키워드 수로 순위를 매기고, 권한 선필터를 지키는가 (#186).
 *
 * <p>{@code RetrievalServiceTest}는 리포지토리를 목킹하므로 이 SQL은 여기서만 실행된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(KeywordChunkJdbcRepository.class)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=update"
})
class KeywordSearchQueryTest extends ContainerTestSupport {

    private static final String MODEL = "keyword-search-test";
    private static final String OWNER = "user-a";
    private static final String OTHER = "user-b";
    private static final List<String> KEYWORDS = List.of("용접기", "롤러", "진동");

    @Autowired
    private DocumentChunkRepository repository;

    @Autowired
    private KeywordChunkJdbcRepository search;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("흔한 단어 하나만 맞은 최신 청크보다 여러 단어가 맞은 옛 청크가 위다 (#186)")
    void 맞은_개수_순() {
        save(1L, "CO2 용접기 3호기 송급 롤러에서 이상 진동이 발생했다.", Visibility.PUBLIC, null); // 3개, 가장 옛것
        save(2L, "CO2 용접기 1호기 송급 롤러 마모.", Visibility.PUBLIC, null);                  // 2개
        for (long id = 3; id <= 12; id++) {                                                     // 1개씩, 최신 10건
            save(id, "CO2 용접기 " + id + "호기 토치 노즐 교체.", Visibility.PUBLIC, null);
        }
        save(13L, "겐트리 크레인 유압 실린더 누유.", Visibility.PUBLIC, null);                  // 0개
        entityManager.flush();

        List<KeywordChunk> found = search.findAll(MODEL, KEYWORDS, 5);

        assertEquals(5, found.size());
        assertEquals(1L, found.get(0).sourceId(), "이전 방식(최신순)이면 3~12번이 다 채워 1번이 빠졌다");
        assertEquals(3, found.get(0).matched());
        assertEquals(2L, found.get(1).sourceId());
        assertEquals(2, found.get(1).matched());
        // 같은 개수끼리는 최신순(chunk_id 내림차순)
        assertEquals(List.of(12L, 11L, 10L), found.subList(2, 5).stream().map(KeywordChunk::sourceId).toList());
    }

    @Test
    @DisplayName("아무 키워드도 맞지 않은 청크는 오지 않는다")
    void 안_맞으면_제외() {
        save(1L, "겐트리 크레인 유압 실린더 누유.", Visibility.PUBLIC, null);
        entityManager.flush();

        assertTrue(search.findAll(MODEL, KEYWORDS, 5).isEmpty());
    }

    @Test
    @DisplayName("권한 선필터 - 남이 소유한 청크는 가장 많이 맞아도 오지 않는다")
    void 권한_선필터() {
        save(1L, "용접기 점검.", Visibility.PUBLIC, null);
        save(2L, "용접기 롤러 점검.", Visibility.OWNER, OWNER);
        save(3L, "용접기 롤러 진동.", Visibility.OWNER, OTHER);
        entityManager.flush();

        List<Long> visible = search.findVisible(MODEL, OWNER, KEYWORDS, 10).stream()
                .map(KeywordChunk::sourceId).toList();

        assertEquals(List.of(2L, 1L), visible);
        assertEquals(3, search.findAll(MODEL, KEYWORDS, 10).size(), "관리자 경로는 전부 본다");
    }

    @Test
    @DisplayName("다른 임베딩 모델의 청크는 섞이지 않고, LIMIT이 바인딩된다")
    void 모델_필터와_limit() {
        save(1L, "용접기 롤러.", Visibility.PUBLIC, null);
        save(2L, "용접기 진동.", Visibility.PUBLIC, null);
        repository.save(chunk(99L, "용접기 롤러 진동.", Visibility.PUBLIC, null, "some-other-model"));
        entityManager.flush();

        List<KeywordChunk> found = search.findAll(MODEL, KEYWORDS, 10);
        assertEquals(2, found.size());
        assertTrue(found.stream().noneMatch(c -> c.sourceId() == 99L));
        assertEquals(1, search.findAll(MODEL, KEYWORDS, 1).size());
    }

    // --- helpers ---

    private void save(long sourceId, String content, Visibility visibility, String ownerUserId) {
        repository.save(chunk(sourceId, content, visibility, ownerUserId, MODEL));
    }

    private static DocumentChunk chunk(long sourceId, String content, Visibility visibility,
                                       String ownerUserId, String model) {
        return DocumentChunk.builder()
                .sourceType(SourceType.WORK_LOG)
                .sourceId(sourceId)
                .chunkIndex(0)
                .languageCode("ko")
                .content(content)
                .contentHash(String.format("%064d", sourceId))
                .embedding(new float[DocumentChunk.EMBEDDING_DIM])
                .embeddingDim(DocumentChunk.EMBEDDING_DIM)
                .embeddingModel(model)
                .visibility(visibility)
                .ownerUserId(ownerUserId)
                .build();
    }
}
