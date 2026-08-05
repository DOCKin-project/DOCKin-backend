package com.DOCKin.ai.model;

import com.DOCKin.worklog.model.Work_logs;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 작업일지의 언어별 번역본.
 *
 * <p><b>테이블명을 {@code translate_logs}에서 {@code work_log_translations}로 맞췄다.</b>
 * 엔티티는 {@code translate_logs}에 매핑되어 있었는데 {@code schema.sql}과 문서(ADR-0003, ADR-0006)는
 * 모두 {@code work_log_translations}를 가리키고 있어, RAG 교차언어 색인의 선결 과제였다.
 * 정리 시점에 {@code translate_logs}는 0행이라 데이터 이전은 없었다.
 *
 * <p>동시에 {@code UNIQUE(log_id, language_code)}를 추가했다. 제약이 없던 탓에
 * 같은 작업일지를 같은 언어로 두 번 번역하면 중복 행이 쌓였고, 교차언어 검색에서
 * <b>같은 문서가 여러 번 색인되어 검색 결과를 오염</b>시킬 수 있었다.
 * 재번역은 새 행이 아니라 기존 행 갱신으로 처리한다({@code FastApiService.saveTranslateLog}).
 *
 * <p>{@code originalTitle}/{@code originalText}는 {@code work_logs}와 중복되는 비정규화 컬럼이지만,
 * <b>번역 시점의 원문</b>을 남겨 원문이 나중에 수정되었을 때 번역본이 어느 버전을 옮긴 것인지 추적할 수 있게 한다.
 */
@Entity
@Table(
        name = "work_log_translations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_log_lang",
                columnNames = {"log_id", "language_code"}
        )
)
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class TranslateLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "translation_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "log_id")
    private Work_logs workLogs;

    private String userId;

    private String traceId;

    @Column(columnDefinition = "TEXT")
    private String originalTitle;

    @Column(columnDefinition = "TEXT")
    private String translatedTitle;

    @Column(columnDefinition = "TEXT")
    private String originalText;

    @Column(columnDefinition = "TEXT")
    private String translatedText;

    /**
     * 번역 대상 언어(ko/vi/en 등).
     *
     * <p>기존 필드명은 {@code targetLang}이었으나 컬럼명을 {@code language_code}로 맞췄다.
     * RAG 색인에서 {@code document_chunks.language_code}로 그대로 옮겨져
     * 교차언어 검색의 recall 측정 기준이 된다.
     */
    @Column(name = "language_code", length = 10)
    private String languageCode;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 재번역 시 새 행을 만들지 않고 기존 행을 갱신한다. 유니크 제약이 중복을 막는다. */
    public void updateTranslation(String originalTitle, String translatedTitle,
                                  String originalText, String translatedText, String traceId) {
        this.originalTitle = originalTitle;
        this.translatedTitle = translatedTitle;
        this.originalText = originalText;
        this.translatedText = translatedText;
        this.traceId = traceId;
    }
}
