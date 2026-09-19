package com.DOCKin.ai.model;

import com.DOCKin.worklog.model.WorkLog;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;

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
    private WorkLog workLogs;

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

    /** 이 번역을 낸 모델(FastAPI 응답의 {@code model}). V9 이전 행은 null. 캐시 히트 응답이 미스 때와 같은 꼴이려고 남긴다. */
    @Column(name = "model", length = 100)
    private String model;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 재번역 시 새 행을 만들지 않고 기존 행을 갱신한다. 유니크 제약이 중복을 막는다. */
    public void updateTranslation(String originalTitle, String translatedTitle,
                                  String originalText, String translatedText, String traceId, String model) {
        this.originalTitle = originalTitle;
        this.translatedTitle = translatedTitle;
        this.originalText = originalText;
        this.translatedText = translatedText;
        this.traceId = traceId;
        this.model = model;
    }

    /**
     * 저장된 번역이 <b>이 원문</b>의 번역인가. 캐시 히트 판단(P2-19-1).
     *
     * <p>{@code work_logs.updated_at}이 아니라 원문 자체를 비교한다 — 승인·반려로 status만 바뀌어도
     * updated_at은 갱신되는데 번역은 낡지 않았다. 표에 번역 당시 원문이 그대로 있으니 그것과 대보면
     * 정확하고 마이그레이션도 없다. 같은 값이라도 다른 것으로 보는 경우가 없도록 {@code equals}다 — trim 하지 않는다.
     */
    public boolean matchesOriginal(String title, String text) {
        return Objects.equals(originalTitle, title) && Objects.equals(originalText, text);
    }
}
