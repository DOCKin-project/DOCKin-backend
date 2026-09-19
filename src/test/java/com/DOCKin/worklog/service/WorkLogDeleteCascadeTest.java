package com.DOCKin.worklog.service;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검증: 번역되고 색인된 작업일지를 지우면 <b>번역·청크가 같이 사라지고, 남의 것은 남는다</b> (#101).
 *
 * <p>전에는 {@code work_log_translations.log_id}가 NO ACTION이라 번역된 일지는 삭제가 500이었고,
 * {@code document_chunks}는 FK가 없어 지운 일지가 RAG 근거로 계속 검색됐다. 여기서는 행을 직접 넣고
 * 서비스로 지운 뒤 세 테이블을 센다. 번역 청크의 {@code source_id}는 {@code translation_id}라 그 경로도 따로 본다.
 *
 * <p>청크 id는 시퀀스 대신 큰 상수 — 시퀀스를 건드리면 Hibernate의 pooled 할당과 겹칠 수 있다.
 */
@SpringBootTest
@DisplayName("작업일지 삭제 - 번역은 DB cascade, RAG 청크는 같은 트랜잭션에서 (#101)")
class WorkLogDeleteCascadeTest extends ContainerTestSupport {

    private static final String AUTHOR = "del-author";
    private static final String OTHER = "del-other";
    private static final String ZERO_VECTOR = "[" + "0,".repeat(383) + "0]";

    @Autowired
    private WorkLogsService workLogsService;
    @Autowired
    private JdbcClient jdbc;

    private Long logId;
    private Long otherLogId;
    private Long translationId;
    private Long otherTranslationId;

    @BeforeEach
    void data() {
        for (String u : new String[]{AUTHOR, OTHER}) {
            jdbc.sql("""
                    INSERT INTO users (user_id, created_at, language_code, name, password,
                                       remaining_leave_days, role, ship_yard_area, tts_enabled)
                    VALUES (:u, now(), 'ko', 'del', 'x', 15, 'USER', 'A', false)
                    ON CONFLICT (user_id) DO NOTHING
                    """).param("u", u).update();
        }
        logId = insertLog(AUTHOR, "지울 일지");
        otherLogId = insertLog(OTHER, "남을 일지");
        translationId = insertTranslation(logId, "en");
        insertTranslation(logId, "vi");
        otherTranslationId = insertTranslation(otherLogId, "en");
        jdbc.sql("INSERT INTO work_log_comments (content, created_at, log_id, user_id) VALUES ('c', now(), :l, :u)")
                .param("l", logId).param("u", AUTHOR).update();

        insertChunk(9_000_000_001L, "WORK_LOG", logId, 0);
        insertChunk(9_000_000_002L, "WORK_LOG", logId, 1);
        insertChunk(9_000_000_003L, "WORK_LOG_TRANSLATION", translationId, 0);
        insertChunk(9_000_000_004L, "WORK_LOG", otherLogId, 0);
        insertChunk(9_000_000_005L, "WORK_LOG_TRANSLATION", otherTranslationId, 0);
        // 다른 종류의 원본이 우연히 같은 id를 가져도 안 지워져야 한다.
        insertChunk(9_000_000_006L, "SAFETY_COURSE", logId, 0);
    }

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM document_chunks WHERE chunk_id BETWEEN 9000000001 AND 9000000010").update();
        jdbc.sql("DELETE FROM work_log_comments WHERE log_id IN (:a, :b)").param("a", logId).param("b", otherLogId).update();
        jdbc.sql("DELETE FROM work_logs WHERE log_id IN (:a, :b)").param("a", logId).param("b", otherLogId).update();
        jdbc.sql("DELETE FROM users WHERE user_id IN (:a, :b)").param("a", AUTHOR).param("b", OTHER).update();
    }

    @Test
    @DisplayName("번역 2건·청크 3개·댓글이 있는 일지를 지우면 500 없이 전부 사라지고, 남의 일지·청크·같은 id의 다른 종류 청크는 남는다")
    void deleteCascades() {
        workLogsService.deleteWorklog(AUTHOR, logId);

        assertThat(count("work_logs", "log_id = " + logId)).isZero();
        assertThat(count("work_log_translations", "log_id = " + logId)).as("V12 cascade").isZero();
        assertThat(count("work_log_comments", "log_id = " + logId)).isZero();
        assertThat(count("document_chunks", "source_type = 'WORK_LOG' AND source_id = " + logId)).isZero();
        assertThat(count("document_chunks", "source_type = 'WORK_LOG_TRANSLATION' AND source_id = " + translationId))
                .as("번역 청크는 translation_id로 걸려 있다").isZero();

        assertThat(count("work_logs", "log_id = " + otherLogId)).isEqualTo(1);
        assertThat(count("work_log_translations", "log_id = " + otherLogId)).isEqualTo(1);
        assertThat(count("document_chunks", "chunk_id IN (9000000004, 9000000005)")).isEqualTo(2);
        assertThat(count("document_chunks", "chunk_id = 9000000006")).as("SAFETY_COURSE의 같은 id는 남는다").isEqualTo(1);
    }

    @Test
    @DisplayName("작성자가 아니면 403이고 아무것도 안 지워진다 - 청크 정리가 검사 뒤에 있다")
    void notAuthorDeletesNothing() {
        assertThatThrownBy(() -> workLogsService.deleteWorklog(OTHER, logId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_LOG_AUTHOR);

        assertThat(count("work_logs", "log_id = " + logId)).isEqualTo(1);
        assertThat(count("document_chunks", "source_type = 'WORK_LOG' AND source_id = " + logId)).isEqualTo(2);
    }

    private Long insertLog(String userId, String title) {
        return jdbc.sql("""
                INSERT INTO work_logs (title, log_text, created_at, updated_at, user_id)
                VALUES (:t, '본문', now(), now(), :u) RETURNING log_id
                """).param("t", title).param("u", userId).query(Long.class).single();
    }

    private Long insertTranslation(Long log, String lang) {
        return jdbc.sql("""
                INSERT INTO work_log_translations (created_at, language_code, original_text, original_title,
                                                   translated_text, translated_title, user_id, log_id)
                VALUES (now(), :lang, '본문', '제목', 'text', 'title', :u, :l) RETURNING translation_id
                """).param("lang", lang).param("u", AUTHOR).param("l", log).query(Long.class).single();
    }

    private void insertChunk(long chunkId, String sourceType, Long sourceId, int index) {
        jdbc.sql("""
                INSERT INTO document_chunks (chunk_id, chunk_index, content, content_hash, created_at, embedding,
                                             embedding_dim, embedding_model, language_code, owner_user_id,
                                             source_id, source_type, visibility)
                VALUES (:id, :idx, 'c', :hash, now(), CAST(:vec AS vector), 384, 'test-model', 'ko', :owner,
                        :sid, :stype, 'OWNER')
                """).param("id", chunkId).param("idx", index).param("hash", "h" + chunkId).param("vec", ZERO_VECTOR)
                .param("owner", AUTHOR).param("sid", sourceId).param("stype", sourceType).update();
    }

    private int count(String table, String where) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + where).query(Integer.class).single();
    }
}
