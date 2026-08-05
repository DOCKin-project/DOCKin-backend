package com.DOCKin.rag.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.rag.dto.RetrievedChunk;
import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import com.DOCKin.rag.repository.DocumentChunkRepository;
import com.DOCKin.rag.repository.NearestChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    @DisplayName("pgvector 텍스트 표현으로 변환한다")
    void 벡터_리터럴() {
        assertEquals("[1.0,-0.5,0.25]", RetrievalService.toVectorLiteral(new float[]{1f, -0.5f, 0.25f}));
    }

    @Test
    @DisplayName("빈 질의는 임베딩 서버를 호출하지 않고 빈 결과를 준다")
    void 빈_질의() {
        assertTrue(retrievalService.search("   ", USER, false, 5).isEmpty());
        assertTrue(retrievalService.search("질문", USER, false, 0).isEmpty());
    }

    @Test
    @DisplayName("질의 벡터 차원이 컬럼과 다르면 DB에 묻지 않고 폴백 가능한 예외를 던진다")
    void 질의_차원_불일치() {
        when(embeddingClient.getModelName()).thenReturn(MODEL);
        when(embeddingClient.embedQuery(anyString())).thenReturn(new float[]{1, 0, 0}); // 3차원

        BusinessException e = assertThrows(BusinessException.class,
                () -> retrievalService.search("안전", USER, false, 5));

        // 이 코드여야 retrieve()가 잡아 키워드 폴백으로 내려보낸다.
        assertEquals(ErrorCode.EMBEDDING_DIMENSION_MISMATCH, e.getErrorCode());
        // 차원이 맞지 않으면 쿼리 자체를 보내지 않는다(DB가 거부할 쿼리다).
        verify(documentChunkRepository, never()).findNearestPublic(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("거리가 가까운 순으로 정렬되고 topK만큼만 반환한다")
    void 거리_정렬과_topK() {
        stubQuery();
        // 두 목록으로 나뉘어 오므로 합친 뒤의 정렬이 맞는지 본다.
        when(documentChunkRepository.findNearestPublic(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.SAFETY_COURSE, 100L, "먼 것", 0.90),
                chunk(2L, SourceType.SAFETY_COURSE, 200L, "가장 가까운 것", 0.05)));
        when(documentChunkRepository.findNearestOwned(eq(MODEL), eq(USER), anyString(), anyInt())).thenReturn(List.of(
                chunk(3L, SourceType.WORK_LOG, 300L, "중간", 0.40)));

        List<RetrievedChunk> result = retrievalService.search("안전", USER, false, 2);

        assertEquals(2, result.size());
        assertEquals("가장 가까운 것", result.get(0).content());
        assertEquals("중간", result.get(1).content());
        // 거리 0.05 -> 유사도 0.95
        assertEquals(0.95, result.get(0).score(), 1e-9);
        assertTrue(result.get(0).score() > result.get(1).score());
    }

    @Test
    @DisplayName("한 문서가 결과를 독식하지 못하도록 원본당 최대 2청크까지만 채택한다")
    void 문서당_상한() {
        stubQuery();
        // 같은 작업일지(sourceId=500)에서 나온 청크 3개가 모두 상위
        when(documentChunkRepository.findNearestPublic(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.WORK_LOG, 500L, "A-0", 0.01),
                chunk(2L, SourceType.WORK_LOG, 500L, "A-1", 0.02),
                chunk(3L, SourceType.WORK_LOG, 500L, "A-2", 0.03),
                chunk(4L, SourceType.WORK_LOG, 600L, "B-0", 0.50)));
        when(documentChunkRepository.findNearestOwned(eq(MODEL), eq(USER), anyString(), anyInt()))
                .thenReturn(List.of());

        List<RetrievedChunk> result = retrievalService.search("용접", USER, false, 3);

        assertEquals(3, result.size());
        long fromSource500 = result.stream().filter(r -> r.sourceId() == 500L).count();
        assertEquals(2, fromSource500, "한 문서에서 2개를 넘게 가져오면 근거가 한쪽으로 쏠린다");
        assertTrue(result.stream().anyMatch(r -> r.sourceId() == 600L), "다른 문서도 섞여야 한다");
    }

    @Test
    @DisplayName("문서당 상한 때문에 버려질 몫까지 감안해 후보를 넉넉히 요청한다")
    void 후보_배수_요청() {
        stubQuery();
        when(documentChunkRepository.findNearestPublic(eq(MODEL), anyString(), anyInt()))
                .thenReturn(List.of(chunk(1L, SourceType.WORK_LOG, 1L, "x", 0.1)));
        when(documentChunkRepository.findNearestOwned(eq(MODEL), eq(USER), anyString(), anyInt()))
                .thenReturn(List.of());

        retrievalService.search("용접", USER, false, 5);

        // topK=5인데 정확히 5건만 가져오면, 한 문서에서 3건이 상위를 차지했을 때 결과가 모자란다.
        verify(documentChunkRepository).findNearestPublic(eq(MODEL), anyString(), eq(20));
    }

    @Test
    @DisplayName("관리자는 권한 선필터 없이 전체 청크를 검색 대상으로 삼는다")
    void 관리자_전체_조회() {
        stubQuery();
        when(documentChunkRepository.findNearestAll(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.WORK_LOG, 700L, "남의 작업일지", 0.2)));

        List<RetrievedChunk> result = retrievalService.search("용접", "admin", true, 5);

        assertEquals(1, result.size());
        // 권한 필터가 걸린 쿼리는 호출되지 않아야 한다.
        verify(documentChunkRepository, never()).findNearestPublic(anyString(), anyString(), anyInt());
        verify(documentChunkRepository, never()).findNearestOwned(anyString(), anyString(), anyString(), anyInt());
    }

    // --- helpers ---

    /** 컬럼 차원과 맞는 질의 벡터를 준다. 값은 무의미하다 - 거리는 리포지토리 목이 정한다. */
    private void stubQuery() {
        when(embeddingClient.getModelName()).thenReturn(MODEL);
        when(embeddingClient.embedQuery(anyString()))
                .thenReturn(new float[DocumentChunk.EMBEDDING_DIM]);
    }

    /**
     * 네이티브 쿼리 투영 스텁. {@code distance}는 코사인 <b>거리</b>이므로 작을수록 가깝다.
     */
    private static NearestChunk chunk(Long chunkId, SourceType type, Long sourceId,
                                      String content, double distance) {
        return new NearestChunk() {
            @Override public Long getChunkId() { return chunkId; }
            @Override public String getSourceType() { return type.name(); }
            @Override public Long getSourceId() { return sourceId; }
            @Override public Integer getChunkIndex() { return 0; }
            @Override public String getContent() { return content; }
            @Override public Double getDistance() { return distance; }
        };
    }
}
