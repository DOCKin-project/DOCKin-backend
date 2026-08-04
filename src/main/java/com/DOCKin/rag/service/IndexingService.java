package com.DOCKin.rag.service;

import com.DOCKin.ai.model.TranslateLog;
import com.DOCKin.ai.repository.TranslateRepository;
import com.DOCKin.rag.model.SourceType;
import com.DOCKin.rag.model.Visibility;
import com.DOCKin.rag.repository.DocumentChunkRepository;
import com.DOCKin.rag.service.ChunkIndexWriter.IndexTarget;
import com.DOCKin.safetyCourse.model.SafetyCourse;
import com.DOCKin.safetyCourse.repository.SafetyCourseRepository;
import com.DOCKin.worklog.model.Work_logs;
import com.DOCKin.worklog.repository.Work_logsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.data.domain.PageRequest;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 원본 문서를 청킹·임베딩해 {@code document_chunks}에 적재하는 배치의 진입점.
 *
 * <p>이 클래스는 <b>페이지 순회와 원본 → {@link IndexTarget} 변환만</b> 담당한다.
 * 실제 적재와 트랜잭션 경계는 {@link ChunkIndexWriter}에 있다(자기호출로 인한
 * {@code @Transactional} 무효화를 피하기 위한 분리).
 *
 * <h3>멱등성과 재시작</h3>
 * 10만 청크 기준 약 20분이 걸리므로(배치 12ms/건 실측) 도중에 중단될 수 있다.
 * 청크마다 원문의 SHA-256을 저장해두고 재실행 시 해시가 같으면 임베딩을 건너뛰므로,
 * <b>몇 번을 다시 돌려도 결과가 같고 끊긴 지점부터 사실상 이어서 진행된다.</b>
 *
 * <h3>알려진 한계</h3>
 * pull 방식(주기 실행)이라 원본 수정이 즉시 반영되지 않는다. 최대 1주기만큼 검색 결과가 낡을 수 있다.
 * CDC(Debezium) 전환 여부는 ADR-0003 3-3의 판단을 따른다.
 *
 * <h3>색인 대상</h3>
 * 작업일지(원문) / 작업일지 번역본 / 안전교육. 번역본까지 같은 벡터 공간에 넣어
 * <b>교차언어 검색</b>이 성립한다 — 언어별 analyzer 구성 없이 다국어 모델만으로 해결되는 지점이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingService {

    /** 한 트랜잭션에서 처리할 원본 문서 수. 임베딩 배치 크기와는 별개다. */
    private static final int PAGE_SIZE = 100;

    private final Work_logsRepository workLogsRepository;
    private final TranslateRepository translateRepository;
    private final SafetyCourseRepository safetyCourseRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final ChunkIndexWriter chunkIndexWriter;
    private final EmbeddingClient embeddingClient;

    @Value("${rag.indexing.enabled}")
    private boolean indexingEnabled;

    @Scheduled(cron = "${rag.indexing.cron}")
    public void scheduledIndexing() {
        if (!indexingEnabled) {
            log.info("[RAG] 인덱싱 배치가 비활성화되어 있어 건너뜁니다.");
            return;
        }
        indexAll();
    }

    /**
     * 전체 코퍼스를 색인한다. 수동 실행과 테스트에서도 호출할 수 있도록 public으로 둔다.
     *
     * @return 이번 실행에서 새로 임베딩한 청크 수 (변경이 없어 건너뛴 것은 제외)
     */
    public int indexAll() {
        long start = System.currentTimeMillis();
        int embedded = 0;

        try {
            embedded += indexWorkLogs();
            embedded += indexTranslations();
            embedded += indexSafetyCourses();
        } catch (Exception e) {
            // 이미 커밋된 페이지는 살아남고, 다음 주기에 남은 분부터 이어서 진행된다(해시 기반 멱등).
            log.error("[RAG] 인덱싱 중단 - 신규 임베딩 {}건까지 반영됨: {}", embedded, e.getMessage(), e);
        }

        long total = documentChunkRepository.countByEmbeddingModel(embeddingClient.getModelName());
        log.info("[RAG] 인덱싱 종료 - 신규 임베딩 {}건 / 전체 청크 {}건 / {}ms",
                embedded, total, System.currentTimeMillis() - start);
        return embedded;
    }

    /** 작업일지는 작성자와 ADMIN만 조회 가능하므로 {@link Visibility#OWNER}로 색인한다. */
    private int indexWorkLogs() {
        int embedded = 0;
        long lastId = 0L;

        while (true) {
            // 커서(keyset) 순회. OFFSET을 쓰지 않는 이유는 findForIndexingAfter의 주석 참고.
            // 페치 조인도 함께 쓴다 - 배치는 OSIV가 없어 트랜잭션 밖에서 member에 접근하면 터지고,
            // 지연 로딩이면 작업일지마다 쿼리가 하나씩 더 나가 N+1이 된다.
            List<Work_logs> batch =
                    workLogsRepository.findForIndexingAfter(lastId, PageRequest.of(0, PAGE_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            List<IndexTarget> targets = batch.stream().map(this::toTarget).toList();
            embedded += chunkIndexWriter.writePage(targets);

            lastId = batch.get(batch.size() - 1).getLogId();
        }
        return embedded;
    }

    private IndexTarget toTarget(Work_logs workLog) {
        // 제목과 본문을 함께 임베딩한다. 제목만 검색어와 맞는 경우를 놓치지 않기 위함이다.
        String text = workLog.getTitle() + "\n" + workLog.getLogText();
        String ownerUserId = workLog.getMember() == null ? null : workLog.getMember().getUserId();
        return new IndexTarget(SourceType.WORK_LOG, workLog.getLogId(), text,
                "ko", Visibility.OWNER, ownerUserId);
    }

    /**
     * 작업일지 번역본 색인 — 교차언어 검색의 핵심.
     *
     * <p>다국어 임베딩 모델을 쓰므로 번역본을 같은 벡터 공간에 넣기만 하면
     * <b>베트남어로 물어도 한국어 원문이, 한국어로 물어도 베트남어 번역본이 검색된다.</b>
     * ADR-0003 3-3이 계획했던 언어별 analyzer 구성(Nori/ICU) 없이 교차언어가 성립하는 지점이다.
     *
     * <p><b>권한은 원본 작업일지를 그대로 따른다.</b> 번역본이라고 아무나 볼 수 있으면
     * 원문의 접근 제어가 무의미해지므로, 원본 작성자를 그대로 소유자로 복사한다.
     *
     * <p>또한 {@code (log_id, language_code)}가 원본과 번역본을 이어주므로,
     * <b>"어떤 질의로 어느 원문이 나와야 하는가"의 정답 라벨이 이미 존재한다.</b>
     * recall@k를 지어내지 않고 측정할 수 있는 근거가 여기 있다.
     */
    private int indexTranslations() {
        int embedded = 0;
        long lastId = 0L;

        while (true) {
            List<TranslateLog> batch =
                    translateRepository.findForIndexingAfter(lastId, PageRequest.of(0, PAGE_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            List<IndexTarget> targets = batch.stream()
                    .map(this::toTarget)
                    .toList();
            embedded += chunkIndexWriter.writePage(targets);

            lastId = batch.get(batch.size() - 1).getId();
        }
        return embedded;
    }

    private IndexTarget toTarget(TranslateLog translation) {
        String text = translation.getTranslatedTitle() + "\n" + translation.getTranslatedText();
        Work_logs source = translation.getWorkLogs();
        String ownerUserId = (source == null || source.getMember() == null)
                ? null : source.getMember().getUserId();

        // sourceId는 번역본 자신의 PK다. 원본 작업일지(WORK_LOG)와 source_type이 달라 충돌하지 않으며,
        // 원본 추적은 language_code와 함께 work_log_translations를 거쳐 가능하다.
        return new IndexTarget(SourceType.WORK_LOG_TRANSLATION, translation.getId(), text,
                translation.getLanguageCode(), Visibility.OWNER, ownerUserId);
    }

    /** 안전교육은 전 근로자가 봐야 하는 내용이므로 {@link Visibility#PUBLIC}이다. */
    private int indexSafetyCourses() {
        int embedded = 0;
        int lastId = 0;

        while (true) {
            List<SafetyCourse> batch =
                    safetyCourseRepository.findForIndexingAfter(lastId, PageRequest.of(0, PAGE_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            List<IndexTarget> targets = batch.stream()
                    .map(c -> new IndexTarget(SourceType.SAFETY_COURSE,
                            c.getCourseId().longValue(),
                            c.getTitle() + "\n" + c.getDescription(),
                            "ko", Visibility.PUBLIC, null))
                    .toList();
            embedded += chunkIndexWriter.writePage(targets);

            lastId = batch.get(batch.size() - 1).getCourseId();
        }
        return embedded;
    }
}
