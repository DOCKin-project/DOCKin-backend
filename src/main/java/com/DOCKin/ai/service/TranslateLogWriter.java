package com.DOCKin.ai.service;

import com.DOCKin.ai.model.TranslateLog;
import com.DOCKin.ai.repository.TranslateRepository;
import com.DOCKin.worklog.model.WorkLog;
import com.DOCKin.worklog.repository.WorkLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업일지 번역 결과를 저장하는 트랜잭션 경계. <b>번역이 끝난 뒤에만</b> 열린다.
 *
 * <p>이전에는 {@code FastApiService.saveTranslateLog} 하나가 조회 → FastAPI 두 번({@code block()},
 * 타임아웃 60초) → 저장을 한 트랜잭션으로 감쌌다. 즉 FastAPI가 느린 동안 HikariCP 커넥션(풀 10)을
 * 물고 있었다 — {@code application.properties}가 {@code ChunkIndexWriter}에 대해 경고한 그 모양이고,
 * M1에서 풀 고갈이 서비스 전체를 멈추는 것을 이미 봤다. 번역 요청 10개가 동시에 느린 FastAPI를
 * 기다리면 출근·작업일지·채팅까지 커넥션을 못 받는다.
 *
 * <p>{@link ChunkIndexWriter}와 같은 이유로 별도 빈이다 — 같은 클래스 안에서 부르면 프록시를
 * 거치지 않아 {@code @Transactional}이 걸리지 않는다. 그쪽은 새벽 배치라 임베딩 호출을 트랜잭션
 * 안에 뒀지만("온라인 경로에서 재사용할 경우 3단계로 쪼개야 한다"), 여기는 온라인 경로다.
 *
 * <p>원본 {@link WorkLog}는 다시 읽지 않고 {@code getReferenceById}로 FK만 잇는다. 제목·본문 원문은
 * 호출자가 트랜잭션 밖에서 읽어 둔 값을 그대로 받는다 — 번역하는 동안 원본이 바뀌었더라도
 * 저장되는 원문은 <b>번역한 그 원문</b>이어야 짝이 맞는다.
 */
@Component
@RequiredArgsConstructor
public class TranslateLogWriter {

    private final TranslateRepository translateRepository;
    private final WorkLogRepository workLogRepository;

    /** 번역 한 건의 입력과 결과. 엔티티가 아니라 값만 넘겨 트랜잭션 밖의 영속 상태에 기대지 않는다. */
    public record Translated(Long logId, String userId, String languageCode, String traceId,
                             String originalTitle, String translatedTitle,
                             String originalText, String translatedText) {}

    /**
     * 같은 작업일지·같은 언어가 있으면 갱신, 없으면 삽입.
     * {@code UNIQUE(log_id, language_code)}가 있어 무조건 save하면 제약 위반이 난다 — 중복 행이 쌓이면
     * RAG 교차언어 색인에서 같은 문서가 여러 번 색인되어 검색 결과가 오염된다.
     */
    @Transactional
    public void upsert(Translated t) {
        translateRepository.findByWorkLogsLogIdAndLanguageCode(t.logId(), t.languageCode())
                .ifPresentOrElse(
                        existing -> existing.updateTranslation(
                                t.originalTitle(), t.translatedTitle(),
                                t.originalText(), t.translatedText(),
                                t.traceId()),
                        () -> translateRepository.save(TranslateLog.builder()
                                .traceId(t.traceId())
                                .workLogs(workLogRepository.getReferenceById(t.logId()))
                                .userId(t.userId())
                                .originalTitle(t.originalTitle())
                                .translatedTitle(t.translatedTitle())
                                .originalText(t.originalText())
                                .translatedText(t.translatedText())
                                .languageCode(t.languageCode())
                                .build()));
    }
}
