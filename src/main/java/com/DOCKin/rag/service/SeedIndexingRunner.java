package com.DOCKin.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 로컬에서 기동 직후 RAG 색인을 한 번 돌린다.
 *
 * <h3>왜 필요한가</h3>
 * 시드({@code db/seed/R__seed_sample.sql})가 넣는 것은 <b>원본뿐</b>이다 --
 * {@code work_logs}, {@code work_log_translations}, {@code safety_courses}.
 * {@code document_chunks}는 임베딩이 있어야 채워지고, 그것은 {@link IndexingService}의 일이다.
 *
 * <p>그런데 그 배치는 {@code rag.indexing.cron} 기본값이 매일 03:00이다.
 * 시드를 넣고 앱을 띄워도 <b>다음 새벽까지 RAG 검색이 빈손</b>이라는 뜻이고,
 * 로컬에서 그걸 기다릴 이유는 없다.
 *
 * <h3>왜 엔드포인트가 아니라 러너인가</h3>
 * 색인 수동 실행 API는 그 자체로 별개의 결정이다 -- 권한을 어떻게 걸 것인지,
 * 오래 걸리는 작업을 동기로 받을 것인지, 중복 실행을 어떻게 막을 것인지가 따라온다.
 * 여기서 필요한 것은 "로컬에서 시드를 색인까지 밀어 넣는 수단" 하나뿐이라
 * 운영에 노출되지 않는 형태로 좁게 만들었다.
 *
 * <h3>이 빈이 만들어지는 조건 -- 둘 다 만족해야 한다</h3>
 * <ul>
 *   <li>{@code local} 프로파일</li>
 *   <li>{@code rag.indexing.on-startup=true} (기본값 false)</li>
 * </ul>
 * 기본값을 false로 둔 이유는 임베딩 서버(TEI)가 떠 있어야 하고, 코퍼스가 크면
 * 색인이 그만큼 오래 걸리기 때문이다. 대량 생성기로 수만 건을 넣어둔 상태라면 수십 분이다.
 *
 * <h3>왜 ApplicationRunner가 아니라 ApplicationReadyEvent + 별도 스레드인가 (#113)</h3>
 * 2026-09-19까지는 {@code ApplicationRunner}였다. 러너는 {@code ApplicationReadyEvent} <b>앞</b>에서
 * 돌고, Spring Boot는 그 이벤트에서 readiness를 {@code ACCEPTING_TRAFFIC}으로 바꾼다. 그래서 색인이
 * 끝날 때까지 {@code /actuator/health}가 {@code readinessState=OUT_OF_SERVICE}로 503이었다 — Tomcat은
 * 떠서 API는 답하는데 컨테이너는 unhealthy고, {@code depends_on: service_healthy}가 걸린 것은 못 뜬다.
 * 코퍼스가 비어 있던 동안은 러너가 즉시 끝나 안 보였고, 작업일지 12만 건을 넣은 AWS 밤 15에서 드러났다.
 *
 * <p>이제는 준비 완료 이벤트를 받아 <b>데몬 스레드 하나</b>에서 돌린다. readiness는 즉시 UP이고
 * 색인은 뒤에서 돈다. 스케줄 배치({@code rag.indexing.cron})와 겹쳐도 {@link IndexingService#indexAll}의
 * Redis 락이 한쪽을 건너뛰게 한다. 실행기를 새로 두지 않는 이유는 이 스레드가 기동당 하나뿐이고
 * 끝나면 사라지기 때문이다 — 풀을 만들면 그 풀의 크기·거부 정책이 또 하나의 결정이 된다.
 */
@Slf4j
@Component
@Profile("seed")
@ConditionalOnProperty(name = "rag.indexing.on-startup", havingValue = "true")
@RequiredArgsConstructor
public class SeedIndexingRunner {

    static final String THREAD_NAME = "seed-indexing";

    private final IndexingService indexingService;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("[RAG] seed 프로파일 - 기동 직후 색인을 뒤에서 시작합니다. (rag.indexing.on-startup=true)");
        Thread.ofPlatform().name(THREAD_NAME).daemon(true).start(this::indexOnce);
    }

    void indexOnce() {
        try {
            // 예외 없이 돌아왔다고 완주한 것이 아니다. indexAll()은 안에서 예외를 잡고
            // 커밋된 분량을 살린 뒤 정상 반환하므로, 완주 여부는 반환값에 물어야 한다.
            IndexingService.IndexRun run = indexingService.indexAll();
            if (run.completed()) {
                log.info("[RAG] 기동 직후 색인 완주 - 신규 임베딩 {}건", run.embedded());
            } else {
                log.error("[RAG] 기동 직후 색인 중단 - 신규 임베딩 {}건까지 반영됨: {}",
                        run.embedded(), run.abortReason());
            }
        } catch (Exception e) {
            // 색인 실패로 앱이 뜨지 않으면 안 된다. 임베딩 서버가 없는 것은 로컬에서 흔한 상황이고,
            // RAG 외의 기능을 보려는 사람까지 막을 이유가 없다.
            log.error("[RAG] 기동 직후 색인 실패 - 임베딩 서버(TEI)가 떠 있는지 확인하세요: {}", e.getMessage());
        }
    }
}
