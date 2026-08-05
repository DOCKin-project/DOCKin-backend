package com.DOCKin.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
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
 * 기동이 그만큼 늦어지기 때문이다. 대량 생성기로 수만 건을 넣어둔 상태라면
 * 기동에 수십 분이 걸린다.
 */
@Slf4j
@Component
@Profile("seed")
@ConditionalOnProperty(name = "rag.indexing.on-startup", havingValue = "true")
@RequiredArgsConstructor
public class SeedIndexingRunner implements ApplicationRunner {

    private final IndexingService indexingService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[RAG] seed 프로파일 - 기동 직후 색인을 시작합니다. (rag.indexing.on-startup=true)");
        try {
            int embedded = indexingService.indexAll();
            log.info("[RAG] 기동 직후 색인 완료 - 신규 임베딩 {}건", embedded);
        } catch (Exception e) {
            // 색인 실패로 앱이 뜨지 않으면 안 된다. 임베딩 서버가 없는 것은 로컬에서 흔한 상황이고,
            // RAG 외의 기능을 보려는 사람까지 막을 이유가 없다.
            log.error("[RAG] 기동 직후 색인 실패 - 임베딩 서버(TEI)가 떠 있는지 확인하세요: {}", e.getMessage());
        }
    }
}
