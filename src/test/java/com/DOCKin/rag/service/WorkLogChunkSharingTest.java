package com.DOCKin.rag.service;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.rag.dto.RetrievedChunk;
import com.DOCKin.rag.model.DocumentChunk;
import com.DOCKin.rag.model.SourceType;
import com.DOCKin.rag.repository.DocumentChunkRepository;
import com.DOCKin.worklog.dto.WorkLogsUpdateRequestDto;
import com.DOCKin.worklog.service.WorkLogReviewService;
import com.DOCKin.worklog.service.WorkLogsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 검증: 승인된 작업일지만 <b>같은 구역</b> 근로자의 RAG 근거가 되고, 상태가 바뀌는 그 트랜잭션에서 반영된다 (ADR-0012).
 *
 * <p>임베딩 서버만 목으로 바꾸고 나머지(승인·수정 서비스, 벡터 SQL, 키워드 폴백 JPQL)는 실제로 탄다.
 * 질의 벡터와 청크 벡터를 같은 축으로 두어 "가까운가"가 아니라 <b>"후보에 들어오는가"</b>만 보게 한다.
 * 이 테스트의 핵심은 수정 경로다 — 배치를 돌리지 않고도 수정 직후 남의 결과에서 사라져야 한다.
 */
@SpringBootTest
@DisplayName("작업일지 RAG 공유 - 승인분만 같은 구역에, 상태 변화는 같은 트랜잭션에서 (ADR-0012)")
class WorkLogChunkSharingTest extends ContainerTestSupport {

    private static final String MODEL = "share-test-model";
    private static final String PREFIX = "shr";
    private static final String AREA = "공유구역";
    private static final String OTHER_AREA = "다른구역";
    private static final String ADMIN = PREFIX + "-admin";
    private static final String AUTHOR = PREFIX + "-author";
    private static final String PEER = PREFIX + "-peer";
    private static final String OUTSIDER = PREFIX + "-outsider";
    private static final String AXIS;

    static {
        StringBuilder sb = new StringBuilder("[1");
        for (int i = 1; i < DocumentChunk.EMBEDDING_DIM; i++) {
            sb.append(",0");
        }
        AXIS = sb.append(']').toString();
    }

    @MockitoBean
    private EmbeddingClient embeddingClient;

    @Autowired private RetrievalService retrievalService;
    @Autowired private WorkLogReviewService reviewService;
    @Autowired private WorkLogsService workLogsService;
    @Autowired private DocumentChunkRepository documentChunkRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long logId;
    private Long translationId;

    @BeforeEach
    void seed() {
        cleanup();
        float[] axis = new float[DocumentChunk.EMBEDDING_DIM];
        axis[0] = 1f;
        when(embeddingClient.getModelName()).thenReturn(MODEL);
        when(embeddingClient.embedQuery(anyString())).thenReturn(axis);

        insertUser(ADMIN, "ADMIN", AREA);
        insertUser(AUTHOR, "USER", AREA);
        insertUser(PEER, "USER", AREA);
        insertUser(OUTSIDER, "USER", OTHER_AREA);

        logId = jdbc.queryForObject("""
                INSERT INTO work_logs (title, log_text, created_at, updated_at, user_id)
                VALUES ('공유 제목', '공유 본문', now(), now(), ?) RETURNING log_id
                """, Long.class, AUTHOR);
        translationId = jdbc.queryForObject("""
                INSERT INTO work_log_translations (created_at, language_code, original_text, original_title,
                                                   translated_text, translated_title, user_id, log_id)
                VALUES (now(), 'vi', '공유 본문', '공유 제목', 'noi dung', 'tieu de', ?, ?) RETURNING translation_id
                """, Long.class, AUTHOR, logId);

        // 색인 배치가 PENDING 일지에 만드는 모양 그대로 — 본인만.
        insertChunk(9_100_000_001L, SourceType.WORK_LOG, logId, "공유 본문 원문");
        insertChunk(9_100_000_002L, SourceType.WORK_LOG_TRANSLATION, translationId, "공유 noi dung");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM document_chunks WHERE embedding_model = ?", MODEL);
        jdbc.update("DELETE FROM work_logs WHERE user_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM users WHERE user_id LIKE ?", PREFIX + "%");
    }

    @Test
    @DisplayName("미승인 일지는 작성자만 근거로 쓴다 - 같은 구역 동료도 못 본다")
    void pendingIsOwnerOnly() {
        assertThat(sources(AUTHOR)).containsExactlyInAnyOrder(
                SourceType.WORK_LOG + ":" + logId, SourceType.WORK_LOG_TRANSLATION + ":" + translationId);
        assertThat(sources(PEER)).isEmpty();
        assertThat(sources(OUTSIDER)).isEmpty();
    }

    @Test
    @DisplayName("승인하면 배치 없이 같은 구역 동료에게 원문·번역본이 열리고, 다른 구역에는 안 열린다")
    void approveOpensToAreaOnly() {
        reviewService.approve(ADMIN, logId, "ok");

        assertThat(sources(PEER)).containsExactlyInAnyOrder(
                SourceType.WORK_LOG + ":" + logId, SourceType.WORK_LOG_TRANSLATION + ":" + translationId);
        assertThat(sources(OUTSIDER)).as("앱 화면도 다른 구역은 403이다").isEmpty();
        assertThat(sources(AUTHOR)).as("작성자는 구역 경로로 계속 본다").hasSize(2);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT visibility, area, owner_user_id FROM document_chunks WHERE chunk_id = 9100000002");
        assertThat(row).containsEntry("visibility", "AREA").containsEntry("area", AREA)
                .containsEntry("owner_user_id", AUTHOR);
    }

    @Test
    @DisplayName("승인된 일지를 작성자가 고치면 같은 트랜잭션에서 닫힌다 - 검토 안 된 내용이 동료 근거로 안 나간다")
    void editClosesImmediately() {
        reviewService.approve(ADMIN, logId, "ok");
        assertThat(sources(PEER)).hasSize(2);

        workLogsService.updateWorklog(AUTHOR, logId,
                WorkLogsUpdateRequestDto.builder().logText("검토 안 된 새 내용").build(), null);

        assertThat(sources(PEER)).isEmpty();
        assertThat(sources(AUTHOR)).as("작성자 본인은 그대로 본다").hasSize(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_chunks WHERE embedding_model = ? AND (visibility <> 'OWNER' OR area IS NOT NULL)",
                Integer.class, MODEL)).isZero();
    }

    @Test
    @DisplayName("반려된 일지는 본인만 - 동료에게 열리지 않는다")
    void rejectStaysOwner() {
        reviewService.reject(ADMIN, logId, "사진 없음");

        assertThat(sources(PEER)).isEmpty();
        assertThat(sources(AUTHOR)).hasSize(2);
    }

    @Test
    @DisplayName("키워드 폴백도 같은 범위다 - 같은 구역 승인분은 찾고, 다른 구역에서는 못 찾는다")
    void keywordFallbackSameScope() {
        assertThat(documentChunkRepository.searchByKeyword(MODEL, PEER, AREA, "공유", PageRequest.of(0, 10)))
                .as("승인 전").isEmpty();

        reviewService.approve(ADMIN, logId, "ok");

        assertThat(documentChunkRepository.searchByKeyword(MODEL, PEER, AREA, "공유", PageRequest.of(0, 10)))
                .hasSize(2);
        assertThat(documentChunkRepository.searchByKeyword(MODEL, OUTSIDER, OTHER_AREA, "공유", PageRequest.of(0, 10)))
                .isEmpty();
        assertThat(documentChunkRepository.searchByKeyword(MODEL, OUTSIDER, null, "공유", PageRequest.of(0, 10)))
                .as("구역을 모르면 좁히는 쪽으로").isEmpty();
    }

    private List<String> sources(String userId) {
        List<RetrievedChunk> found = retrievalService.search("공유", userId, false, 10);
        return found.stream().map(c -> c.sourceType() + ":" + c.sourceId()).toList();
    }

    private void insertUser(String userId, String role, String area) {
        jdbc.update("""
                INSERT INTO users (user_id, name, password, role, language_code,
                                   ship_yard_area, remaining_leave_days, tts_enabled, work_shift, created_at)
                VALUES (?, ?, ?, ?, 'ko', ?, 15, false, 'MORNING', ?)
                """, userId, userId, "$2a$10$" + "0".repeat(53), role, area, LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    private void insertChunk(long chunkId, SourceType type, Long sourceId, String content) {
        jdbc.update("""
                INSERT INTO document_chunks (chunk_id, chunk_index, content, content_hash, created_at, embedding,
                                             embedding_dim, embedding_model, language_code, owner_user_id,
                                             source_id, source_type, visibility)
                VALUES (?, 0, ?, ?, now(), CAST(? AS vector), 384, ?, 'ko', ?, ?, ?, 'OWNER')
                """, chunkId, content, "h" + chunkId, AXIS, MODEL, AUTHOR, sourceId, type.name());
    }
}
