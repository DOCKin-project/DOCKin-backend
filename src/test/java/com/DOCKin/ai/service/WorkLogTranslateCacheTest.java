package com.DOCKin.ai.service;

import com.DOCKin.ai.dto.TranslateDomain;
import com.DOCKin.ai.quota.AiQuotaExceededException;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검증: 작업일지 번역이 <b>같은 원문·같은 언어면 FastAPI에 다시 가지 않고, 원문이 바뀌면 다시 간다</b> (P2-19-1).
 *
 * <p>2026-09-16까지는 {@code work_log_translations}에 저장만 하고 읽지 않았다 — 같은 일지를 다시 보면 FastAPI 두 번
 * (제목·본문)과 한도 1이 매번 들었다. 단언은 응답이 아니라 <b>스텁에 도착한 요청 수</b>와 <b>Redis 한도 카운터</b>다.
 * 응답이 같아도 뒤에서 FastAPI를 불렀으면 캐시가 아니다.
 *
 * <p>스텁은 요청마다 다른 문장을 돌려준다(호출 번호를 붙인다). 그래야 "두 번째 응답이 첫 번째와 같다"가
 * 캐시의 증거가 된다 — 늘 같은 문장을 주는 스텁이면 재호출해도 같아 보인다.
 */
@SpringBootTest
@TestPropertySource(properties = "ai.quota.daily.worklog-translate=3")
@DisplayName("작업일지 번역 캐시 - 같은 원문은 FastAPI에 안 가고 한도도 안 깎인다")
class WorkLogTranslateCacheTest extends ContainerTestSupport {

    private static HttpServer stub;
    private static final AtomicInteger CALLS = new AtomicInteger();

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
            int n = CALLS.incrementAndGet();
            byte[] body = ("{\"translated\":\"translated #" + n + "\",\"model\":\"stub-v1\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
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
    private RedissonClient redisson;
    @Autowired
    private Clock clock;

    @Test
    @DisplayName("두 번째 요청은 FastAPI 0회·한도 0 - 원문을 고치면 다시 2회·한도 1 - 상한+1은 FastAPI 0회")
    void 같은_원문은_캐시_바뀐_원문은_재번역() {
        String userId = "cache-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO users (user_id, name, password, role, language_code,
                                   ship_yard_area, remaining_leave_days, tts_enabled, work_shift, created_at)
                VALUES (?, ?, ?, 'USER', 'ko', 'A', 15, false, 'MORNING', ?)
                """, userId, "캐시", "$2a$10$" + "0".repeat(53), LocalDateTime.now());
        Long logId = jdbc.queryForObject("""
                INSERT INTO work_logs (title, log_text, created_at, updated_at, user_id)
                VALUES ('캐시 제목', '캐시 본문', now(), now(), ?)
                RETURNING log_id
                """, Long.class, userId);
        int before = CALLS.get();

        // 1. 첫 요청 — 미스. 제목·본문 두 번 나가고 한도 1.
        TranslateDomain.Response first = fastApiService.saveTranslateLog(
                logId, new TranslateDomain.Request("ko", "en", "trace-1"), userId);
        assertThat(CALLS.get() - before).as("첫 요청은 제목·본문 두 번").isEqualTo(2);
        assertThat(quotaUsed(userId)).isEqualTo(1);
        assertThat(first.model()).isEqualTo("stub-v1");

        // 2. 같은 요청 — 히트. FastAPI 0회, 한도 그대로, 본문·모델은 첫 번째 것, traceId만 이번 것.
        TranslateDomain.Response second = fastApiService.saveTranslateLog(
                logId, new TranslateDomain.Request("ko", "en", "trace-2"), userId);
        assertThat(CALLS.get() - before).as("히트면 FastAPI에 안 간다").isEqualTo(2);
        assertThat(quotaUsed(userId)).as("히트는 한도를 안 깎는다").isEqualTo(1);
        assertThat(second.title()).isEqualTo(first.title());
        assertThat(second.translated()).isEqualTo(first.translated());
        assertThat(second.model()).as("V9 model 컬럼에서 온다").isEqualTo("stub-v1");
        assertThat(second.traceId()).isEqualTo("trace-2");
        assertThat(rows(logId, "en")).isEqualTo(1);

        // 3. 다른 언어 — 키가 (log_id, language_code)라 미스.
        fastApiService.saveTranslateLog(logId, new TranslateDomain.Request("ko", "vi", "trace-3"), userId);
        assertThat(CALLS.get() - before).isEqualTo(4);
        assertThat(quotaUsed(userId)).isEqualTo(2);

        // 4. 승인·반려처럼 updated_at만 바뀐 경우 — 원문이 같으니 여전히 히트. (updated_at을 신호로 썼다면 여기서 재번역됐다)
        jdbc.update("UPDATE work_logs SET updated_at = now() + interval '1 hour' WHERE log_id = ?", logId);
        fastApiService.saveTranslateLog(logId, new TranslateDomain.Request("ko", "en", "trace-4"), userId);
        assertThat(CALLS.get() - before).as("updated_at만 바뀐 건 재번역 사유가 아니다").isEqualTo(4);

        // 5. 본문을 고치면 — 미스. 다시 두 번 나가고 행은 새로 안 생기고 덮어써진다.
        jdbc.update("UPDATE work_logs SET log_text = '캐시 본문 (수정)' WHERE log_id = ?", logId);
        TranslateDomain.Response third = fastApiService.saveTranslateLog(
                logId, new TranslateDomain.Request("ko", "en", "trace-5"), userId);
        assertThat(CALLS.get() - before).as("원문이 바뀌면 다시 번역한다").isEqualTo(6);
        assertThat(quotaUsed(userId)).isEqualTo(3);
        assertThat(third.translated()).isNotEqualTo(first.translated());
        assertThat(rows(logId, "en")).as("재번역은 덮어쓴다 - UNIQUE(log_id, language_code)").isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT original_text, translated_text, model FROM work_log_translations WHERE log_id = ? AND language_code = 'en'",
                logId);
        assertThat(row.get("original_text")).isEqualTo("캐시 본문 (수정)");
        assertThat(row.get("translated_text")).isEqualTo(third.translated());
        assertThat(row.get("model")).isEqualTo("stub-v1");

        // 6. 고친 뒤 한 번 더 — 새 원문으로 히트. 한도 3을 다 썼지만 히트는 검사 자체를 안 거친다.
        fastApiService.saveTranslateLog(logId, new TranslateDomain.Request("ko", "en", "trace-6"), userId);
        assertThat(CALLS.get() - before).isEqualTo(6);
        assertThat(quotaUsed(userId)).isEqualTo(3);

        // 7. 상한+1 — 미스인데 한도가 찼다. 429이고 FastAPI에는 안 간다. 이 호출도 세어진다(P2-19 "호출 전 INCR").
        //    2026-09-19 전엔 AiQuotaWiringTest가 컨트롤러에서 봤다 — 한도가 서비스로 들어오며 여기로 옮겨 왔다.
        assertThatThrownBy(() -> fastApiService.saveTranslateLog(
                logId, new TranslateDomain.Request("ko", "ja", "trace-7"), userId))
                .isInstanceOf(AiQuotaExceededException.class);
        assertThat(CALLS.get() - before).as("한도 초과는 FastAPI를 부르지 않는다").isEqualTo(6);
        assertThat(quotaUsed(userId)).isEqualTo(4);
        assertThat(rows(logId, "ja")).isZero();
    }

    /** {@code AiQuota}와 같은 키. 종류·사용자·오늘(같은 Clock) — 자정 넘김은 AiQuotaRedisTest가 본다. */
    private long quotaUsed(String userId) {
        String key = "quota:ai:worklog-translate:" + userId + ":" + ZonedDateTime.now(clock).toLocalDate();
        return redisson.getAtomicLong(key).get();
    }

    private int rows(Long logId, String lang) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM work_log_translations WHERE log_id = ? AND language_code = ?",
                Integer.class, logId, lang);
    }
}
