package com.DOCKin.ai.service;

import com.DOCKin.ai.dto.TranslateDomain;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증: 작업일지 번역이 <b>FastAPI를 기다리는 동안 DB 커넥션을 물지 않는다.</b>
 *
 * <p>이전 코드는 조회 → FastAPI {@code block()} → 저장이 {@code @Transactional} 하나였다. 번역이
 * 느리면 그 시간만큼 HikariCP 커넥션(풀 10)이 잠겨 있었고, 이 테스트는 그 상태에서 <b>활성 커넥션 1</b>로
 * 실패한다. 쪼갠 뒤에는 0이다. 상태 코드나 저장 결과가 아니라 <b>대기 중의 풀 상태</b>가 단언이다.
 *
 * <p>FastAPI 자리에 JDK 내장 HTTP 서버를 둔다. 요청이 오면 테스트가 풀어줄 때까지 응답을 잡고 있는다 —
 * "느린 FastAPI"를 시간이 아니라 래치로 만들어 타이밍에 기대지 않는다. {@code @Primary} WebClient로
 * 갈아 끼우는 것은 {@code ContainerTestSupport}가 base-url을 동적 프로퍼티로 이미 덮고 있어
 * 하위 클래스에서 다시 덮을 수 없기 때문이다(상위 클래스 등록이 나중에 적용된다).
 */
@SpringBootTest
@DisplayName("작업일지 번역 - 트랜잭션 경계")
class TranslateTransactionBoundaryTest extends ContainerTestSupport {

    private static HttpServer stub;
    private static final CountDownLatch REQUEST_ARRIVED = new CountDownLatch(2); // 제목·본문
    private static final CountDownLatch RELEASE = new CountDownLatch(1);

    @TestConfiguration
    static class StubFastApi {
        @Bean
        @Primary
        WebClient stubFastApiWebClient() {
            return WebClient.builder()
                    .baseUrl("http://localhost:" + stub.getAddress().getPort())
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build();
        }
    }

    @BeforeAll
    static void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stub.createContext("/api/translate", exchange -> {
            REQUEST_ARRIVED.countDown();
            try {
                RELEASE.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"translated\":\"translated text\",\"model\":\"stub\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        // 제목·본문이 동시에 오므로 핸들러 스레드가 둘 필요하다.
        stub.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @Autowired
    private FastApiService fastApiService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("FastAPI가 응답을 잡고 있는 동안 활성 커넥션은 0이다 - 쪼개기 전엔 1이었다")
    void 번역_대기_중_커넥션_점유_없음() throws Exception {
        String userId = "tx-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO users (user_id, name, password, role, language_code,
                                   ship_yard_area, remaining_leave_days, tts_enabled, work_shift, created_at)
                VALUES (?, ?, ?, 'USER', 'ko', 'A', 15, false, 'MORNING', ?)
                """, userId, "경계", "$2a$10$" + "0".repeat(53), LocalDateTime.now());
        Long logId = jdbc.queryForObject("""
                INSERT INTO work_logs (title, log_text, created_at, updated_at, user_id)
                VALUES ('경계 제목', '경계 본문', now(), now(), ?)
                RETURNING log_id
                """, Long.class, userId);

        var pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        assertThat(pool.getActiveConnections()).as("시작 전 기준선").isZero();

        CompletableFuture<TranslateDomain.Response> inFlight = CompletableFuture.supplyAsync(() ->
                fastApiService.saveTranslateLog(logId, new TranslateDomain.Request("ko", "en", "trace-tx"), userId));

        assertThat(REQUEST_ARRIVED.await(10, TimeUnit.SECONDS)).as("FastAPI 스텁에 두 요청이 도착").isTrue();

        // 핵심 단언 — 번역을 기다리는 지금, DB 커넥션을 쥔 스레드가 없어야 한다.
        assertThat(pool.getActiveConnections())
                .as("FastAPI 대기 중 활성 커넥션. 1이면 트랜잭션이 외부 호출을 감싸고 있다")
                .isZero();

        RELEASE.countDown();
        TranslateDomain.Response response = inFlight.get(15, TimeUnit.SECONDS);

        assertThat(response.translated()).isEqualTo("translated text");
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM work_log_translations WHERE log_id = ? AND language_code = 'en'",
                Integer.class, logId);
        assertThat(rows).as("풀어준 뒤 저장은 정상적으로 된다").isEqualTo(1);
    }
}
