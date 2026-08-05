package com.DOCKin.rag.service;

import com.DOCKin.rag.dto.RetrievedChunk;
import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import com.DOCKin.rag.model.Visibility;
import com.DOCKin.rag.repository.ChunkVector;
import com.DOCKin.rag.repository.DocumentChunkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    private static final String MODEL = "test-model";
    private static final String USER = "10001";

    @Mock
    private DocumentChunkRepository documentChunkRepository;
    @Mock
    private EmbeddingClient embeddingClient;

    @InjectMocks
    private RetrievalService retrievalService;

    // minScore는 @Value 주입 필드이며, 단위 테스트에서는 primitive 기본값 0.0이 그대로 쓰인다(임계값 비활성).

    @Test
    @DisplayName("코사인 유사도 - 같은 방향은 1, 직교는 0, 반대는 -1")
    void 코사인_유사도() {
        float[] a = {1, 0, 0};

        assertEquals(1.0, RetrievalService.cosine(a, new float[]{1, 0, 0}), 1e-6);
        assertEquals(0.0, RetrievalService.cosine(a, new float[]{0, 1, 0}), 1e-6);
        assertEquals(-1.0, RetrievalService.cosine(a, new float[]{-1, 0, 0}), 1e-6);
        // 크기가 달라도 방향이 같으면 1이다(정규화 여부와 무관하게 동작해야 한다).
        assertEquals(1.0, RetrievalService.cosine(a, new float[]{5, 0, 0}), 1e-6);
    }

    @Test
    @DisplayName("빈 질의는 임베딩 서버를 호출하지 않고 빈 결과를 준다")
    void 빈_질의() {
        assertTrue(retrievalService.search("   ", USER, false, 5).isEmpty());
        assertTrue(retrievalService.search("질문", USER, false, 0).isEmpty());
    }

    @Test
    @DisplayName("유사도가 높은 순으로 정렬되고 topK만큼만 반환한다")
    void 유사도_정렬과_topK() {
        stubQuery(new float[]{1, 0, 0});
        when(documentChunkRepository.findSearchTargets(MODEL, USER)).thenReturn(List.of(
                vector(1L, SourceType.SAFETY_COURSE, 100L, new float[]{0, 1, 0}),    // cos 0
                vector(2L, SourceType.SAFETY_COURSE, 200L, new float[]{1, 0, 0}),    // cos 1
                vector(3L, SourceType.SAFETY_COURSE, 300L, new float[]{0.7f, 0.7f, 0}) // cos ~0.707
        ));
        stubContents(
                chunk(1L, SourceType.SAFETY_COURSE, 100L, 0, "직교"),
                chunk(2L, SourceType.SAFETY_COURSE, 200L, 0, "동일"),
                chunk(3L, SourceType.SAFETY_COURSE, 300L, 0, "중간"));

        List<RetrievedChunk> result = retrievalService.search("안전", USER, false, 2);

        assertEquals(2, result.size());
        assertEquals("동일", result.get(0).content());
        assertEquals("중간", result.get(1).content());
        assertTrue(result.get(0).score() > result.get(1).score());
    }

    @Test
    @DisplayName("한 문서가 결과를 독식하지 못하도록 원본당 최대 2청크까지만 채택한다")
    void 문서당_상한() {
        stubQuery(new float[]{1, 0, 0});
        // 같은 작업일지(sourceId=500)에서 나온 청크 3개가 모두 최고 점수
        when(documentChunkRepository.findSearchTargets(MODEL, USER)).thenReturn(List.of(
                vector(1L, SourceType.WORK_LOG, 500L, new float[]{1, 0, 0}),
                vector(2L, SourceType.WORK_LOG, 500L, new float[]{1, 0, 0}),
                vector(3L, SourceType.WORK_LOG, 500L, new float[]{1, 0, 0}),
                vector(4L, SourceType.WORK_LOG, 600L, new float[]{0.5f, 0.5f, 0})
        ));
        stubContents(
                chunk(1L, SourceType.WORK_LOG, 500L, 0, "A-0"),
                chunk(2L, SourceType.WORK_LOG, 500L, 1, "A-1"),
                chunk(3L, SourceType.WORK_LOG, 500L, 2, "A-2"),
                chunk(4L, SourceType.WORK_LOG, 600L, 0, "B-0"));

        List<RetrievedChunk> result = retrievalService.search("용접", USER, false, 3);

        assertEquals(3, result.size());
        long fromSource500 = result.stream().filter(r -> r.sourceId() == 500L).count();
        assertEquals(2, fromSource500, "한 문서에서 2개를 넘게 가져오면 근거가 한쪽으로 쏠린다");
        assertTrue(result.stream().anyMatch(r -> r.sourceId() == 600L), "다른 문서도 섞여야 한다");
    }

    @Test
    @DisplayName("질의와 차원이 다른 청크는 비교하지 않고 건너뛴다")
    void 차원_불일치_제외() {
        stubQuery(new float[]{1, 0, 0});
        when(documentChunkRepository.findSearchTargets(MODEL, USER)).thenReturn(List.of(
                vector(1L, SourceType.SAFETY_COURSE, 100L, new float[]{1, 0, 0, 0}), // 4차원 - 구모델 잔여
                vector(2L, SourceType.SAFETY_COURSE, 200L, new float[]{1, 0, 0})
        ));
        stubContents(chunk(2L, SourceType.SAFETY_COURSE, 200L, 0, "정상"));

        List<RetrievedChunk> result = retrievalService.search("안전", USER, false, 5);

        assertEquals(1, result.size());
        assertEquals("정상", result.get(0).content());
    }

    @Test
    @DisplayName("관리자는 권한 선필터 없이 전체 청크를 검색 대상으로 삼는다")
    void 관리자_전체_조회() {
        stubQuery(new float[]{1, 0, 0});
        when(documentChunkRepository.findAllSearchTargets(MODEL)).thenReturn(List.of(
                vector(1L, SourceType.WORK_LOG, 700L, new float[]{1, 0, 0})));
        stubContents(chunk(1L, SourceType.WORK_LOG, 700L, 0, "남의 작업일지"));

        List<RetrievedChunk> result = retrievalService.search("용접", "admin", true, 5);

        assertEquals(1, result.size());
        // 권한 필터가 걸린 쿼리는 호출되지 않아야 한다.
        verify(documentChunkRepository).findAllSearchTargets(MODEL);
    }

    // --- helpers ---

    private void stubQuery(float[] queryVector) {
        when(embeddingClient.getModelName()).thenReturn(MODEL);
        when(embeddingClient.embedQuery(anyString())).thenReturn(queryVector);
    }

    private void stubContents(DocumentChunk... chunks) {
        when(documentChunkRepository.findByChunkIdIn(anyList())).thenReturn(List.of(chunks));
    }

    private static ChunkVector vector(Long chunkId, SourceType type, Long sourceId, float[] v) {
        return new ChunkVector(chunkId, type, sourceId, v);
    }

    /** {@code chunkId}는 {@code @GeneratedValue}라 빌더로 지정할 수 없어 리플렉션으로 채운다. */
    private static DocumentChunk chunk(Long chunkId, SourceType type, Long sourceId,
                                       int chunkIndex, String content) {
        DocumentChunk chunk = DocumentChunk.builder()
                .sourceType(type)
                .sourceId(sourceId)
                .chunkIndex(chunkIndex)
                .languageCode("ko")
                .content(content)
                .contentHash("hash")
                .embedding(new float[]{})
                .embeddingDim(3)
                .embeddingModel(MODEL)
                .visibility(Visibility.PUBLIC)
                .build();
        try {
            Field field = DocumentChunk.class.getDeclaredField("chunkId");
            field.setAccessible(true);
            field.set(chunk, chunkId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return chunk;
    }
}
