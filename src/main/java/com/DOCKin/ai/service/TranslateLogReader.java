package com.DOCKin.ai.service;

import com.DOCKin.ai.repository.TranslateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 저장된 작업일지 번역을 읽는 트랜잭션 경계 (P2-19-1). {@link TranslateLogWriter}의 읽기 쪽 짝이다.
 *
 * <h3>왜 서비스에서 리포지토리를 바로 부르지 않는가</h3>
 * {@code FastApiService.saveTranslateLog}는 {@code NOT_SUPPORTED}다 — FastAPI를 기다리는 동안 커넥션을 물지
 * 않으려고. 그런데 <b>{@code NOT_SUPPORTED} 스코프 안에서 리포지토리 쿼리 메서드(derived {@code findBy…}·{@code @Query})를
 * 부르면 그 커넥션이 스코프가 끝날 때까지 잡혀 있다.</b> {@code findById}·{@code count}는 안 그렇다. 2026-09-19 프로브:
 * <pre>
 *   findById inScope=0 | count inScope=0 | jpql inScope=1 | derived inScope=1 | 별도 빈 @Transactional(readOnly) inScope=0
 * </pre>
 * 기존 코드가 {@code findById}만 써서 우연히 안전했던 것이고, 캐시 조회({@code findByWorkLogsLogIdAndLanguageCode})를
 * 서비스에 바로 넣자 {@code TranslateTransactionBoundaryTest}가 "FastAPI 대기 중 활성 커넥션 1"로 빨개졌다.
 * 여기처럼 <b>다른 빈의 짧은 readOnly 트랜잭션</b>으로 감싸면 그 트랜잭션이 끝날 때 커넥션이 돌아간다.
 *
 * <p>엔티티가 아니라 값만 돌려준다 — 트랜잭션 밖으로 영속 상태를 내보내지 않는다({@code Translated}와 같은 이유).
 * 지연 로딩되는 {@code workLogs}는 건드리지 않으므로 여기서 읽을 것은 컬럼 몇 개뿐이다.
 */
@Component
@RequiredArgsConstructor
public class TranslateLogReader {

    /** 저장된 번역 한 건에서 캐시 판단·응답에 필요한 값. */
    public record Cached(String translatedTitle, String translatedText, String model, LocalDateTime createdAt) {}

    /**
     * 같은 작업일지·같은 언어의 저장된 번역이 <b>이 원문</b>의 것이면 돌려준다. 원문이 다르면(수정됨) 비어 있다.
     * 판단은 {@code TranslateLog.matchesOriginal} — 원문 비교이지 {@code updated_at}이 아니다.
     */
    @Transactional(readOnly = true)
    public Optional<Cached> findCached(Long logId, String languageCode, String originalTitle, String originalText) {
        return translateRepository.findByWorkLogsLogIdAndLanguageCode(logId, languageCode)
                .filter(t -> t.matchesOriginal(originalTitle, originalText))
                .map(t -> new Cached(t.getTranslatedTitle(), t.getTranslatedText(), t.getModel(), t.getCreatedAt()));
    }

    private final TranslateRepository translateRepository;
}
