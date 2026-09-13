package com.DOCKin.ai.repository;

import com.DOCKin.ai.model.TranslateLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TranslateRepository extends JpaRepository<TranslateLog,Long> {

    /**
     * 재번역 시 기존 행을 찾아 갱신하기 위한 조회.
     * {@code UNIQUE(log_id, language_code)}가 생겼으므로 무조건 save하면 제약 위반이 난다.
     */
    Optional<TranslateLog> findByWorkLogsLogIdAndLanguageCode(Long logId, String languageCode);

    /**
     * RAG 교차언어 색인 전용 커서(keyset) 조회.
     *
     * <p>{@code workLogs}와 그 {@code member}까지 페치 조인하는 이유:
     * 번역본의 공개 범위는 <b>원본 작업일지의 권한을 그대로 따라야 한다.</b>
     * 번역본이라고 아무나 볼 수 있으면 원문 접근 제어가 무의미해지므로,
     * 색인 시 원본 작성자를 {@code document_chunks.owner_user_id}로 복사한다.
     * 배치는 OSIV가 없어 지연 로딩 상태로 트랜잭션 밖에서 접근하면 터지고, N+1도 발생한다.
     */
    @Query("""
            SELECT t FROM TranslateLog t
            JOIN FETCH t.workLogs w
            LEFT JOIN FETCH w.member
            WHERE t.id > :lastId
            ORDER BY t.id ASC
            """)
    List<TranslateLog> findForIndexingAfter(@Param("lastId") Long lastId, Pageable pageable);
}
