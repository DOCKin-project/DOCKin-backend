package com.DOCKin.ai.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0002 2-1 검증용 벤치마크: 제목/본문 번역 두 번의 호출을
 * 순차 block() vs Mono.zip() 병렬화로 실제 응답시간을 비교한다.
 * FastAPI 대신 300ms 지연 후 응답하는 로컬 스텁 서버를 띄워서 측정한다.
 */
class TranslateParallelBenchmarkTest {

    private static final int DELAY_MS = 300;
    private HttpServer server;
    private WebClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/translate", exchange -> {
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException ignored) {
            }
            byte[] body = "{\"translated\":\"ok\",\"model\":\"stub\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        // 기본 실행자는 요청을 한 번에 하나씩만 처리해 병렬 요청도 서버 쪽에서 직렬화된다.
        // 실제 FastAPI(Uvicorn 등)처럼 동시 요청을 처리할 수 있도록 스레드풀을 지정한다.
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        client = WebClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort())
                .build();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private Mono<Map> callTranslate() {
        return client.post().uri("/api/translate")
                .bodyValue(Map.of("text", "x"))
                .retrieve()
                .bodyToMono(Map.class);
    }

    @Test
    void sequentialBlockingVsMonoZip() {
        // 커넥션 풀/이벤트루프 워밍업 (콜드 스타트 비용을 측정 대상에서 제외하기 위함)
        for (int i = 0; i < 5; i++) {
            callTranslate().block();
        }

        long sequentialStart = System.nanoTime();
        callTranslate().block();
        callTranslate().block();
        long sequentialMs = (System.nanoTime() - sequentialStart) / 1_000_000;

        long parallelStart = System.nanoTime();
        Mono.zip(callTranslate(), callTranslate()).block();
        long parallelMs = (System.nanoTime() - parallelStart) / 1_000_000;

        System.out.println("[BENCHMARK] warmed-up: sequential(2x block) = " + sequentialMs + "ms, Mono.zip = " + parallelMs + "ms");

        assertTrue(parallelMs < sequentialMs, "Mono.zip 병렬화가 순차 blocking보다 빨라야 한다");
    }
}
