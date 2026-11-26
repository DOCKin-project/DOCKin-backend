package com.DOCKin.rag.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.rag.dto.RetrievalResult;
import com.DOCKin.rag.dto.RetrievedChunk;
import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import com.DOCKin.rag.repository.KeywordChunk;
import com.DOCKin.rag.repository.KeywordChunkJdbcRepository;
import com.DOCKin.rag.repository.NearestChunk;
import com.DOCKin.rag.repository.NearestChunkJdbcRepository;
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
import static org.mockito.ArgumentMatchers.any;
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
    private KeywordChunkJdbcRepository keywordChunkJdbcRepository;
    @Mock
    private NearestChunkJdbcRepository nearestChunkJdbcRepository;
    @Mock
    private EmbeddingClient embeddingClient;
    /** 구역 조회(ADR-0012). 스텁하지 않으면 Optional.empty — 구역 승인분 조회를 건너뛴다. */
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private RerankClient rerankClient;

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
        verify(nearestChunkJdbcRepository, never()).findNearestPublic(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("거리가 가까운 순으로 정렬되고 topK만큼만 반환한다")
    void 거리_정렬과_topK() {
        stubQuery();
        // 두 목록으로 나뉘어 오므로 합친 뒤의 정렬이 맞는지 본다.
        when(nearestChunkJdbcRepository.findNearestPublic(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.SAFETY_COURSE, 100L, "먼 것", 0.90),
                chunk(2L, SourceType.SAFETY_COURSE, 200L, "가장 가까운 것", 0.05)));
        when(nearestChunkJdbcRepository.findNearestOwned(eq(MODEL), eq(USER), anyString(), anyInt())).thenReturn(List.of(
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
        when(nearestChunkJdbcRepository.findNearestPublic(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.WORK_LOG, 500L, "A-0", 0.01),
                chunk(2L, SourceType.WORK_LOG, 500L, "A-1", 0.02),
                chunk(3L, SourceType.WORK_LOG, 500L, "A-2", 0.03),
                chunk(4L, SourceType.WORK_LOG, 600L, "B-0", 0.50)));
        when(nearestChunkJdbcRepository.findNearestOwned(eq(MODEL), eq(USER), anyString(), anyInt()))
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
        when(nearestChunkJdbcRepository.findNearestPublic(eq(MODEL), anyString(), anyInt()))
                .thenReturn(List.of(chunk(1L, SourceType.WORK_LOG, 1L, "x", 0.1)));
        when(nearestChunkJdbcRepository.findNearestOwned(eq(MODEL), eq(USER), anyString(), anyInt()))
                .thenReturn(List.of());

        retrievalService.search("용접", USER, false, 5);

        // topK=5인데 정확히 5건만 가져오면, 한 문서에서 3건이 상위를 차지했을 때 결과가 모자란다.
        verify(nearestChunkJdbcRepository).findNearestPublic(eq(MODEL), anyString(), eq(20));
    }

    @Test
    @DisplayName("관리자는 권한 선필터 없이 전체 청크를 검색 대상으로 삼는다")
    void 관리자_전체_조회() {
        stubQuery();
        when(nearestChunkJdbcRepository.findNearestAll(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.WORK_LOG, 700L, "남의 작업일지", 0.2)));

        List<RetrievedChunk> result = retrievalService.search("용접", "admin", true, 5);

        assertEquals(1, result.size());
        // 권한 필터가 걸린 쿼리는 호출되지 않아야 한다.
        verify(nearestChunkJdbcRepository, never()).findNearestPublic(anyString(), anyString(), anyInt());
        verify(nearestChunkJdbcRepository, never()).findNearestOwned(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("폴백 키워드 - 글자·숫자만 남기고 2글자 이상을 중복 없이 긴 것부터, 최대 MAX개")
    void 폴백_키워드() {
        assertEquals(List.of("CO2", "용접기", "송급", "롤러", "이상", "진동"),
                RetrievalService.fallbackKeywords("CO2 용접기, 송급 롤러 이상 진동? 용접기 a"));
        assertTrue(RetrievalService.fallbackKeywords("  ? ! a ").isEmpty());
        assertTrue(RetrievalService.fallbackKeywords(null).isEmpty());

        String longQuery = "가나 다라 마바 사아 자차 카타 파하 거너 더러 머버";
        assertEquals(RetrievalService.MAX_FALLBACK_KEYWORDS,
                RetrievalService.fallbackKeywords(longQuery).size());
    }

    @Test
    @DisplayName("임베딩 장애 시 키워드 전부를 한 번에 넘겨 폴백하고, 결과 순서를 그대로 쓴다(#186)")
    void 키워드_폴백() {
        when(embeddingClient.getModelName()).thenReturn(MODEL);
        when(embeddingClient.embedQuery(anyString()))
                .thenThrow(new BusinessException(ErrorCode.EMBEDDING_SERVER_ERROR));
        when(keywordChunkJdbcRepository.findVisible(MODEL, USER, null, List.of("용접기", "롤러", "진동"), 5))
                .thenReturn(List.of(
                        new KeywordChunk(2L, SourceType.WORK_LOG, 20L, 0, "셋 다 맞음", 3),
                        new KeywordChunk(9L, SourceType.WORK_LOG, 90L, 0, "하나만 맞음", 1)));

        RetrievalResult result = retrievalService.retrieve("용접기 롤러 진동", USER, false, 5);

        assertEquals(RetrievalResult.RetrievalMode.KEYWORD, result.mode());
        assertEquals(List.of("셋 다 맞음", "하나만 맞음"),
                result.chunks().stream().map(RetrievedChunk::content).toList());
        assertEquals(0.0, result.chunks().get(0).score(), 0.0);
        verify(keywordChunkJdbcRepository, never()).findAll(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("관리자 폴백은 권한 필터 없는 쿼리를 쓴다")
    void 관리자_키워드_폴백() {
        when(embeddingClient.getModelName()).thenReturn(MODEL);
        when(embeddingClient.embedQuery(anyString()))
                .thenThrow(new BusinessException(ErrorCode.EMBEDDING_SERVER_ERROR));
        when(keywordChunkJdbcRepository.findAll(MODEL, List.of("용접기"), 5)).thenReturn(List.of());

        RetrievalResult result = retrievalService.retrieve("용접기", "admin", true, 5);

        assertTrue(result.chunks().isEmpty());
        verify(keywordChunkJdbcRepository, never()).findVisible(anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("하이브리드가 꺼져 있으면 정상 경로에서 키워드 쿼리를 부르지 않고 모드는 VECTOR다")
    void 하이브리드_꺼짐() {
        stubQuery();
        when(nearestChunkJdbcRepository.findNearestPublic(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.WORK_LOG, 10L, "벡터", 0.2)));

        RetrievalResult result = retrievalService.retrieve("용접기 롤러", USER, false, 5);

        assertEquals(RetrievalResult.RetrievalMode.VECTOR, result.mode());
        verify(keywordChunkJdbcRepository, never()).findVisible(anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("하이브리드: 양쪽에 다 있는 청크가 위로, 키워드로만 맞은 청크도 들어온다(점수 0.0) - 모드 HYBRID")
    void 하이브리드_RRF() {
        hybrid();
        stubQuery();
        // 벡터 순위: 1(가장 가까움) > 2 > 3
        when(nearestChunkJdbcRepository.findNearestPublic(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.WORK_LOG, 10L, "의미만 가까움", 0.10),
                chunk(2L, SourceType.WORK_LOG, 20L, "양쪽 다", 0.20),
                chunk(3L, SourceType.WORK_LOG, 30L, "벡터 3위", 0.30)));
        // 키워드 순위: 2 > 9(벡터에 없음)
        when(keywordChunkJdbcRepository.findVisible(eq(MODEL), eq(USER), any(), eq(List.of("5호기", "롤러")), anyInt()))
                .thenReturn(List.of(
                        new KeywordChunk(2L, SourceType.WORK_LOG, 20L, 0, "양쪽 다", 2),
                        new KeywordChunk(9L, SourceType.WORK_LOG, 90L, 0, "호기 번호만 맞음", 2)));

        RetrievalResult result = retrievalService.retrieve("5호기 롤러", USER, false, 4);

        assertEquals(RetrievalResult.RetrievalMode.HYBRID, result.mode());
        // RRF(k=60): 2 = 1/62+1/61, 1 = 1/61, 9 = 1/62, 3 = 1/63
        assertEquals(List.of(2L, 1L, 9L, 3L), result.chunks().stream().map(RetrievedChunk::chunkId).toList());
        assertEquals(0.80, result.chunks().get(0).score(), 1e-9, "점수는 RRF가 아니라 코사인 유사도");
        assertEquals(0.0, result.chunks().get(2).score(), 0.0, "키워드로만 온 청크는 0.0");
    }

    @Test
    @DisplayName("하이브리드: 합친 뒤에도 문서당 2개 상한이 걸린다")
    void 하이브리드_문서당_상한() {
        hybrid();
        List<RetrievedChunk> fused = retrievalService.fuse(
                List.of(chunk(1L, SourceType.WORK_LOG, 10L, "a", 0.1),
                        chunk(2L, SourceType.WORK_LOG, 10L, "b", 0.2)),
                List.of(new KeywordChunk(3L, SourceType.WORK_LOG, 10L, 2, "c", 1),
                        new KeywordChunk(4L, SourceType.WORK_LOG, 40L, 0, "d", 1)),
                5);
        // RRF: 1·3 = 1/61(동점 → 유사도 있는 1이 앞), 2·4 = 1/62. 문서 10은 1·3으로 두 칸이 차서 2가 빠진다
        assertEquals(List.of(1L, 3L, 4L), fused.stream().map(RetrievedChunk::chunkId).toList());
    }

    @Test
    @DisplayName("하이브리드: 키워드 쿼리가 실패해도 벡터 결과로 답한다")
    void 하이브리드_키워드_실패() {
        hybrid();
        stubQuery();
        when(nearestChunkJdbcRepository.findNearestPublic(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.WORK_LOG, 10L, "벡터", 0.2)));
        when(keywordChunkJdbcRepository.findVisible(anyString(), anyString(), any(), any(), anyInt()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));

        RetrievalResult result = retrievalService.retrieve("용접기 롤러", USER, false, 5);

        assertEquals(List.of(1L), result.chunks().stream().map(RetrievedChunk::chunkId).toList());
    }

    @Test
    @DisplayName("재순위: 재순위 점수 순으로 다시 줄 세운다 - 응답 score는 여전히 코사인 유사도")
    void 재순위_순서() {
        rerank(0.0);
        stubQuery();
        when(nearestChunkJdbcRepository.findNearestPublic(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.WORK_LOG, 10L, "가까움", 0.10),
                chunk(2L, SourceType.WORK_LOG, 20L, "정답", 0.20),
                chunk(3L, SourceType.WORK_LOG, 30L, "중간", 0.30)));
        when(rerankClient.score(eq("롤러 진동"), eq(List.of("가까움", "정답", "중간"))))
                .thenReturn(java.util.Optional.of(new double[]{0.10, 0.90, 0.50}));

        RetrievalResult result = retrievalService.retrieve("롤러 진동", USER, false, 5);

        assertEquals(List.of(2L, 3L, 1L), result.chunks().stream().map(RetrievedChunk::chunkId).toList());
        assertEquals(0.80, result.chunks().get(0).score(), 1e-9);
    }

    @Test
    @DisplayName("재순위: 하한보다 낮은 후보는 버리고, 전부 낮으면 근거 없음(NONE)이다 (#203)")
    void 재순위_하한() {
        rerank(0.30);
        stubQuery();
        when(nearestChunkJdbcRepository.findNearestPublic(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.WORK_LOG, 10L, "a", 0.10),
                chunk(2L, SourceType.WORK_LOG, 20L, "b", 0.20)));
        when(rerankClient.score(anyString(), any())).thenReturn(java.util.Optional.of(new double[]{0.05, 0.40}));

        assertEquals(List.of(2L), retrievalService.retrieve("q", USER, false, 5).chunks().stream()
                .map(RetrievedChunk::chunkId).toList());

        when(rerankClient.score(anyString(), any())).thenReturn(java.util.Optional.of(new double[]{0.05, 0.10}));
        assertEquals(RetrievalResult.RetrievalMode.NONE, retrievalService.retrieve("q", USER, false, 5).mode());
    }

    @Test
    @DisplayName("재순위: 서버가 실패하면 재순위 전 순서로 답한다")
    void 재순위_실패() {
        rerank(0.30);
        stubQuery();
        when(nearestChunkJdbcRepository.findNearestPublic(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.WORK_LOG, 10L, "a", 0.10),
                chunk(2L, SourceType.WORK_LOG, 20L, "b", 0.20)));
        when(rerankClient.score(anyString(), any())).thenReturn(java.util.Optional.empty());

        assertEquals(List.of(1L, 2L), retrievalService.retrieve("q", USER, false, 5).chunks().stream()
                .map(RetrievedChunk::chunkId).toList());
    }

    @Test
    @DisplayName("재순위: 후보는 candidates개까지만 넘기고, 문서당 상한은 재순위 뒤에 건다")
    void 재순위_후보수와_상한() {
        rerank(0.0);
        org.springframework.test.util.ReflectionTestUtils.setField(retrievalService, "rerankCandidates", 3);
        stubQuery();
        when(nearestChunkJdbcRepository.findNearestPublic(eq(MODEL), anyString(), anyInt())).thenReturn(List.of(
                chunk(1L, SourceType.WORK_LOG, 10L, "a", 0.10),
                chunk(2L, SourceType.WORK_LOG, 10L, "b", 0.11),
                chunk(3L, SourceType.WORK_LOG, 10L, "c", 0.12),
                chunk(4L, SourceType.WORK_LOG, 40L, "d", 0.13)));
        when(rerankClient.score(anyString(), eq(List.of("a", "b", "c"))))
                .thenReturn(java.util.Optional.of(new double[]{0.2, 0.9, 0.8}));

        // 재순위 순서 2,3,1 → 문서 10은 두 개까지라 1이 빠진다. 4는 후보 3개 밖이라 버려진다
        assertEquals(List.of(2L, 3L), retrievalService.retrieve("q", USER, false, 5).chunks().stream()
                .map(RetrievedChunk::chunkId).toList());
    }

    private void rerank(double minScore) {
        org.springframework.test.util.ReflectionTestUtils.setField(retrievalService, "rerankEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(retrievalService, "rerankCandidates", 20);
        org.springframework.test.util.ReflectionTestUtils.setField(retrievalService, "rerankMinScore", minScore);
    }

    private void hybrid() {
        org.springframework.test.util.ReflectionTestUtils.setField(retrievalService, "hybridEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(retrievalService, "rrfK", 60);
    }

    // --- helpers ---

    /** 컬럼 차원과 맞는 질의 벡터를 준다. 값은 무의미하다 - 거리는 리포지토리 목이 정한다. */
    @Test
    @DisplayName("구역이 있는 사용자는 같은 구역 승인분도 후보로 합친다 - 구역을 모르면 그 조회를 하지 않는다 (ADR-0012)")
    void 구역_승인분_합침() {
        stubQuery();
        when(memberRepository.findByUserId(USER)).thenReturn(java.util.Optional.of(
                com.DOCKin.member.model.Member.builder().userId(USER).shipYardArea("A").build()));
        when(nearestChunkJdbcRepository.findNearestInArea(eq(MODEL), eq("A"), anyString(), anyInt())).thenReturn(List.of(
                chunk(4L, SourceType.WORK_LOG, 400L, "동료의 승인 일지", 0.10)));

        List<RetrievedChunk> result = retrievalService.search("안전", USER, false, 5);

        assertEquals(List.of("동료의 승인 일지"), result.stream().map(RetrievedChunk::content).toList());

        when(memberRepository.findByUserId("ghost")).thenReturn(java.util.Optional.empty());
        retrievalService.search("안전", "ghost", false, 5);
        verify(nearestChunkJdbcRepository, never()).findNearestInArea(anyString(), eq(null), anyString(), anyInt());
    }

    private void stubQuery() {
        when(embeddingClient.getModelName()).thenReturn(MODEL);
        when(embeddingClient.embedQuery(anyString()))
                .thenReturn(new float[DocumentChunk.EMBEDDING_DIM]);
    }

    /**
     * 검색 결과 한 건. {@code distance}는 코사인 <b>거리</b>이므로 작을수록 가깝다.
     */
    private static NearestChunk chunk(Long chunkId, SourceType type, Long sourceId,
                                      String content, double distance) {
        return new NearestChunk(chunkId, type, sourceId, 0, content, distance);
    }
}
