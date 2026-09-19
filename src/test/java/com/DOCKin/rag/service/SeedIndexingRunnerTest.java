package com.DOCKin.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #113 — 기동 직후 색인이 준비 완료 이벤트를 막지 않는다.
 *
 * <p>색인이 끝나기 전에 {@code onReady()}가 돌아와야 readiness가 UP이 된다. 색인을 래치로 붙들어 두고
 * 리스너가 그 사이에 돌아오는지, 색인은 호출 스레드가 아니라 데몬 스레드에서 도는지를 본다.
 * 스프링 컨텍스트는 안 띄운다 — 여기서 고정하려는 것은 "이벤트 앞에서 동기로 돈다"였던 모양뿐이다.
 */
class SeedIndexingRunnerTest {

    @Test
    @DisplayName("onReady는 색인이 끝나기 전에 돌아오고, 색인은 seed-indexing 데몬 스레드에서 돈다")
    void 색인이_준비_완료를_막지_않는다() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> indexingThread = new AtomicReference<>();
        IndexingService indexingService = mock(IndexingService.class);
        when(indexingService.indexAll()).thenAnswer(inv -> {
            indexingThread.set(Thread.currentThread());
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return IndexingService.IndexRun.skipped(0, 0);
        });

        new SeedIndexingRunner(indexingService).onReady();   // 색인이 붙들려 있어도 여기서 돌아와야 한다

        assertTrue(started.await(5, TimeUnit.SECONDS), "색인이 시작되지 않았다");
        Thread t = indexingThread.get();
        assertEquals(SeedIndexingRunner.THREAD_NAME, t.getName());
        assertTrue(t.isDaemon(), "색인 스레드가 종료를 막으면 안 된다");
        assertTrue(t != Thread.currentThread(), "색인이 이벤트 스레드에서 동기로 돌았다 — 그게 #113이다");
        release.countDown();
    }
}
