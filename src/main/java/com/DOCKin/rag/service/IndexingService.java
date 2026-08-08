package com.DOCKin.rag.service;

import com.DOCKin.ai.model.TranslateLog;
import com.DOCKin.ai.repository.TranslateRepository;
import com.DOCKin.rag.model.SourceType;
import com.DOCKin.rag.model.Visibility;
import com.DOCKin.rag.repository.DocumentChunkRepository;
import com.DOCKin.rag.service.ChunkIndexWriter.IndexTarget;
import com.DOCKin.safetyCourse.model.SafetyCourse;
import com.DOCKin.safetyCourse.repository.SafetyCourseRepository;
import com.DOCKin.worklog.model.WorkLog;
import com.DOCKin.worklog.repository.WorkLogRepository;
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
 * 오래 걸리는 작업이라 도중에 중단될 수 있다.
 * 청크마다 원문의 SHA-256을 저장해두고 재실행 시 해시가 같으면 임베딩을 건너뛰므로,
 * <b>몇 번을 다시 돌려도 결과가 같고 끊긴 지점부터 사실상 이어서 진행된다.</b>
 *
 * <p><b>"10만 청크 약 20분(12ms/건)"이라고 적혀 있었으나 틀린 값이다.</b> TEI를 직접 재보니
 * 청크 길이에 크게 좌우된다 — 149자는 배치 32건에서 46ms/건, 317자는 153ms/건이다.
 * 게다가 색인 중에는 TEI가 PostgreSQL과 CPU를 두고 경쟁한다.
 * 실제로 12만 청크 규모에서 <b>시간 단위</b>가 걸린다(SERVICE-SCALE-ASSUMPTIONS 6-8).
 *
 * <h3>알려진 한계</h3>
 * pull 방식(주기 실행)이라 원본 수정이 즉시 반영되지 않는다. 최대 1주기만큼 검색 결과가 낡을 수 있다.
 * CDC(Debezium) 전환 여부는 ADR-0003 3-3의 판단을 따른다.
 *
 * <h3>색인 대상</h3>
 * 작업일지(원문) / 작업일지 번역본 / 안전교육. 셋 다 같은 벡터 공간에 들어가며
 * 색인 경로에 <b>언어별 분기가 없다</b> — 언어별 analyzer 구성 없이 다국어 모델만으로 해결되는 지점이다.
 *
 * <p><b>번역본 색인은 교차언어 검색의 성립 조건이 아니다.</b> {@code CrossLingualRetrievalTest}가
 * <b>한국어 코퍼스만</b> 두고 영어 5/5, 베트남어 4/5로 통과한다 — 다국어 모델을 쓰는 이상
 * 번역본이 없어도 교차언어는 성립한다. 번역본을 함께 넣는 것은 <b>품질 보강</b>이며,
 * 저장·임베딩 비용이 배로 늘고 원문과 번역본이 top-k 자리를 중복으로 차지하는 대가가 따른다
 * (백로그 P2-8-2 / P2-8-3). 그 이득은 아직 측정하지 않았다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingService {

    /** 한 트랜잭션에서 처리할 원본 문서 수. 임베딩 배치 크기와는 별개다. */
    private static final int PAGE_SIZE = 100;

    private final WorkLogRepository workLogsRepository;
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

        // 누산기를 필드가 아닌 지역 객체로 두되 하위 메서드에 넘긴다.
        //
        // 이전에는 각 indexXxx()가 자기 지역변수에 세고 반환값을 더했다. 그러면 예외가 나는 순간
        // 그 메서드가 반환하지 못해 **이미 커밋된 페이지의 수가 통째로 사라진다.**
        // 실제로 10만 건 색인이 중간에 실패했을 때 1,837건이 커밋되어 있는데도 로그에는
        // "신규 임베딩 0건까지 반영됨"이 찍혔다. 재시작 지점을 알려주려고 만든 로그인데
        // 그 숫자가 틀리면 "얼마나 진행됐나"를 DB에 직접 물어봐야 한다.
        Progress progress = new Progress();

        try {
            indexWorkLogs(progress);
            indexTranslations(progress);
            indexSafetyCourses(progress);
        } catch (Exception e) {
            // 이미 커밋된 페이지는 살아남고, 다음 주기에 남은 분부터 이어서 진행된다(해시 기반 멱등).
            log.error("[RAG] 인덱싱 중단 - 신규 임베딩 {}건까지 반영됨: {}", progress.embedded, e.getMessage(), e);
        }
        int embedded = progress.embedded;

        long total = documentChunkRepository.countByEmbeddingModel(embeddingClient.getModelName());
        log.info("[RAG] 인덱싱 종료 - 신규 임베딩 {}건 / 전체 청크 {}건 / {}ms",
                embedded, total, System.currentTimeMillis() - start);
        return embedded;
    }

    /** 작업일지는 작성자와 ADMIN만 조회 가능하므로 {@link Visibility#OWNER}로 색인한다. */
    private void indexWorkLogs(Progress progress) {
        long lastId = 0L;

        while (true) {
            // 커서(keyset) 순회. OFFSET을 쓰지 않는 이유는 findForIndexingAfter의 주석 참고.
            // 페치 조인도 함께 쓴다 - 배치는 OSIV가 없어 트랜잭션 밖에서 member에 접근하면 터지고,
            // 지연 로딩이면 작업일지마다 쿼리가 하나씩 더 나가 N+1이 된다.
            List<WorkLog> batch =
                    workLogsRepository.findForIndexingAfter(lastId, PageRequest.of(0, PAGE_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            List<IndexTarget> targets = batch.stream().map(this::toTarget).toList();
            progress.embedded += chunkIndexWriter.writePage(targets);

            lastId = batch.get(batch.size() - 1).getLogId();
        }
    }

    private IndexTarget toTarget(WorkLog workLog) {
        // 제목과 본문을 함께 임베딩한다. 제목만 검색어와 맞는 경우를 놓치지 않기 위함이다.
        String text = workLog.getTitle() + "\n" + workLog.getLogText();
        // getMember()는 무조건 non-null이다 - work_logs.user_id가 NOT NULL이다(P2-15-6, V5).
        // 원래 여기엔 null 가드가 있었고 같은 값을 읽는 WorkLogDto.from에는 없었다. 즉 한 컬럼을
        // 두고 두 경로가 서로 다른 전제를 갖고 있었다. 어느 쪽 코드를 맞추는 대신 컬럼을
        // NOT NULL로 만들어 전제를 하나로 만들었다.
        //
        // 가드를 남겨두면 owner_user_id가 null인 청크를 만들 수 있고, 그건 소유자 기반
        // 접근 제어(Visibility.OWNER)가 걸리지 않는 행이라 조용히 지나가지 않는다.
        String ownerUserId = workLog.getMember().getUserId();
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
    private void indexTranslations(Progress progress) {
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
            progress.embedded += chunkIndexWriter.writePage(targets);

            lastId = batch.get(batch.size() - 1).getId();
        }
    }

    private IndexTarget toTarget(TranslateLog translation) {
        String text = translation.getTranslatedTitle() + "\n" + translation.getTranslatedText();
        WorkLog source = translation.getWorkLogs();
        // getMember()의 검사는 위와 같은 이유로 없앴다(P2-15-6, V5).
        // source 자신의 검사는 남긴다 - NOT NULL로 만든 것은 work_logs.user_id 하나이고,
        // work_log_translations.log_id는 여전히 nullable이다. 그 근거(NULL 0건, 생성 경로가
        // 전부 필수로 요구함)를 이 FK에 대해서는 확인한 적이 없다.
        String ownerUserId = source == null ? null : source.getMember().getUserId();

        // sourceId는 번역본 자신의 PK다. 원본 작업일지(WORK_LOG)와 source_type이 달라 충돌하지 않으며,
        // 원본 추적은 language_code와 함께 work_log_translations를 거쳐 가능하다.
        return new IndexTarget(SourceType.WORK_LOG_TRANSLATION, translation.getId(), text,
                translation.getLanguageCode(), Visibility.OWNER, ownerUserId);
    }

    /** 안전교육은 전 근로자가 봐야 하는 내용이므로 {@link Visibility#PUBLIC}이다. */
    private void indexSafetyCourses(Progress progress) {
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
            progress.embedded += chunkIndexWriter.writePage(targets);

            lastId = batch.get(batch.size() - 1).getCourseId();
        }
    }

    /**
     * 진행 수를 하위 메서드와 공유하기 위한 누산기.
     *
     * <p>반환값으로 세면 예외가 나는 순간 <b>이미 커밋된 분량이 로그에서 사라진다.</b>
     * 값을 참조로 들고 다녀야 실패 시점까지의 진행이 남는다.
     */
    private static final class Progress {
        private int embedded = 0;
    }
}
