package com.DOCKin.rag.service;

import com.DOCKin.ai.repository.TranslateRepository;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.rag.repository.DocumentChunkRepository;
import com.DOCKin.rag.service.IndexingService.IndexRun;
import com.DOCKin.safetyCourse.repository.SafetyCourseRepository;
import com.DOCKin.worklog.repository.WorkLogRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 검증: 색인 실행 상호배제({@code rag:indexing:lock})가 <b>프로세스 밖</b>에서 걸리고, 끝나면 풀리고,
 * Redis가 죽으면 <b>연다</b> (ADR-0009 2절 둘째 행).
 *
 * <p>이 락은 2026-08-13 밤 1 사고(기동 색인과 03:00 cron이 겹쳐 {@code uk_chunk_source} 충돌) 뒤에
 * 들어왔는데, ADR-0009가 표를 만들 때까지 "검증: 없음 [테스트 필요]"였다. 여기서 채운다.
 *
 * <p>{@code AiQuotaRedisTest}와 같은 방식 — 스프링 없이 컨테이너의 Redis에 직접 붙고, 저장소는 전부
 * 빈 결과를 돌려주는 목이다. 코퍼스가 비어 있으면 색인은 "락 잡기 → 세 저장소를 한 번씩 물어보기 →
 * 락 놓기"만 남으므로 락의 동작만 보인다. 열림 경로는 이 테스트만의 Redis를 띄웠다가 죽인다.
 */
class IndexingLockRedisTest extends ContainerTestSupport {

    private static final String LOCK_KEY = "rag:indexing:lock";

    private static RedissonClient redisson;

    @BeforeAll
    static void connectRedis() {
        redisson = connect(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void disconnectRedis() {
        redisson.shutdown();
    }

    private static RedissonClient connect(String host, int port) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(2)
                // 죽은 Redis 테스트가 기본값(3회 × 3초)으로 기다리지 않게. 재는 것은 "열리는가"다.
                .setRetryAttempts(0)
                .setTimeout(500)
                .setConnectTimeout(3000);
        return Redisson.create(config);
    }

    /** 저장소가 전부 비어 있는 색인기. 반환한 배열의 [1]이 작업일지 저장소 목이다 — 훑었는지 verify하려고. */
    private static Object[] service(RedissonClient client) {
        WorkLogRepository workLogs = mock(WorkLogRepository.class);
        when(workLogs.findForIndexingAfter(anyLong(), any())).thenReturn(List.of());
        TranslateRepository translations = mock(TranslateRepository.class);
        when(translations.findForIndexingAfter(anyLong(), any())).thenReturn(List.of());
        SafetyCourseRepository courses = mock(SafetyCourseRepository.class);
        when(courses.findForIndexingAfter(anyInt(), any())).thenReturn(List.of());
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        when(chunks.countByEmbeddingModel(any())).thenReturn(0L);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.getModelName()).thenReturn("test-model");

        IndexingService service = new IndexingService(workLogs, translations, courses, chunks,
                mock(ChunkIndexWriter.class), embedding, client);
        return new Object[] {service, workLogs};
    }

    @Test
    @DisplayName("다른 실행이 락을 쥐고 있으면 기다리지 않고 건너뛴다 - 코퍼스를 훑지 않았으므로 완주도 아니다")
    void 겹치면_건너뜀() throws Exception {
        Object[] s = service(redisson);
        IndexingService service = (IndexingService) s[0];
        WorkLogRepository workLogs = (WorkLogRepository) s[1];

        // Redisson 락은 스레드에 묶인다. "다른 실행"은 다른 스레드가 쥐고 있어야 하고, 그 스레드가
        // 살아 있어야 워치독이 갱신한다. 그래서 단일 스레드 실행기에서 잡고 같은 실행기에서 놓는다.
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            RLock lock = redisson.getLock(LOCK_KEY);
            other.submit((Runnable) lock::lock).get();
            try {
                IndexRun run = service.indexAll();

                assertThat(run.completed()).isFalse();
                assertThat(run.abortReason()).contains("건너뜀");
                verify(workLogs, never()).findForIndexingAfter(anyLong(), any());
            } finally {
                other.submit((Runnable) lock::unlock).get();
            }
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    @DisplayName("끝나면 락을 놓는다 - 다음 주기가 들어올 수 있다")
    void 끝나면_해제() {
        IndexingService service = (IndexingService) service(redisson)[0];

        assertThat(service.indexAll().completed()).isTrue();
        assertThat(redisson.getLock(LOCK_KEY).isLocked()).isFalse();
        assertThat(service.indexAll().completed()).isTrue();
    }

    @Test
    @DisplayName("Redis가 죽으면 상호배제 없이 진행하고 결과를 돌려준다 - 열림 (ADR-0009 2절)")
    void 레디스_장애_시_열림() {
        // 공유 컨테이너를 끌 수는 없으니 이 테스트만의 Redis를 하나 띄웠다가 죽인다.
        try (GenericContainer<?> dying = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)) {
            dying.start();
            RedissonClient client = connect(dying.getHost(), dying.getMappedPort(6379));
            try {
                Object[] s = service(client);
                IndexingService service = (IndexingService) s[0];
                WorkLogRepository workLogs = (WorkLogRepository) s[1];

                dying.stop();

                // 락을 못 잡아도 색인은 돈다 — 그리고 끝난 뒤 락을 놓는 자리에서도 예외가 새면 안 된다.
                // 코퍼스를 다 훑고 나서 finally에서 죽으면 완주 결과가 통째로 사라진다.
                IndexRun run = service.indexAll();

                assertThat(run.completed()).isTrue();
                verify(workLogs).findForIndexingAfter(anyLong(), any());
            } finally {
                client.shutdown();
            }
        }
    }
}
