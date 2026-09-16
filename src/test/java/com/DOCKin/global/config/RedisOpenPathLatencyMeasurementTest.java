package com.DOCKin.global.config;

import com.DOCKin.ai.quota.AiQuota;
import com.DOCKin.ai.quota.AiQuotaKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 측정 (ADR-0009 4절): Redis가 죽었을 때 <b>"연다"는 몇 초 뒤인가.</b>
 *
 * <h3>왜 재는가</h3>
 * 출근 분산락과 AI 한도는 Redis 장애에 열린다 — 예외를 잡고 통과시킨다. 그런데 예외가 나기까지
 * 요청이 얼마나 붙잡히는지는 잰 적이 없다. {@code AiQuotaRedisTest}는 {@code retryAttempts 0 ·
 * timeout 500ms}로 <b>줄여서</b> "열리는가"만 봤고, 운영 {@code RedissonConfig}는 Redisson 기본값
 * ({@code retryAttempts 3 · retryInterval 1500ms · timeout 3000ms · connectTimeout 10000ms})이다.
 * 열림은 "결국 통과한다"이지 "바로 통과한다"가 아니다. 07:00~07:30 출근 피크에 Redis가 멎으면
 * 요청마다 그 시간을 기다리고, Tomcat 스레드(기본 200)가 그만큼 잠긴다.
 *
 * <h3>무엇을 재는가</h3>
 * 클라이언트는 운영 설정 그대로다 — {@link RedissonConfig#redissonClient()}를 직접 불러 만든다.
 * Redis는 이 테스트 전용 컨테이너를 띄우고 두 가지로 죽인다.
 * <ul>
 *   <li><b>정지</b>(컨테이너 제거, 포트 닫힘) — 커널이 즉시 {@code Connection refused}를 준다.
 *       프로세스가 죽거나 컨테이너가 내려간 경우.</li>
 *   <li><b>멈춤</b>({@code docker pause}, 연결은 살아 있고 응답만 없음) — 타임아웃까지 기다려야 한다.
 *       호스트 과부하·네트워크 블랙홀·{@code maxmemory} 도달 뒤 fsync 지연 같은 경우.</li>
 * </ul>
 * 각각에서 {@code AiQuota.consume}(INCR + EXPIRE)과 {@code RLock.tryLock(3s, 3s)}
 * ({@code AttendanceService}의 값)이 <b>돌아오기까지</b>의 시간을 잰다 — 예외는 안에서 잡히므로
 * 호출자가 보는 것은 지연뿐이다. 순차 호출로 한 요청의 대기를, 동시 16으로 풀(8)이 마를 때
 * 대기가 얹히는지를 본다. 멈춤은 풀어 준 뒤 첫 성공까지도 잰다 — Redis가 돌아오면 바로 닫히는가.
 *
 * <h3>돌리는 법</h3>
 * {@code MEASURE_REDIS_OPEN_PATH=true}일 때만 돈다. 멈춤 조건에서 호출 하나가 십수 초라 전체가
 * 분 단위이고, 컨테이너를 pause하는 것은 CI 러너에서 매번 할 일이 아니다.
 * <pre>MEASURE_REDIS_OPEN_PATH=true ./gradlew test --tests '*RedisOpenPathLatency*'</pre>
 *
 * <h3>결과를 단정하지 않는다</h3>
 * 측정이다. 단언은 "열렸다"(예외가 밖으로 안 나왔다)뿐이다. 숫자는 ADR-0009 4절에 옮긴다.
 */
@EnabledIfEnvironmentVariable(named = "MEASURE_REDIS_OPEN_PATH", matches = "true",
        disabledReason = "Redis 장애 대기 시간 측정 — 분 단위라 MEASURE_REDIS_OPEN_PATH=true일 때만")
class RedisOpenPathLatencyMeasurementTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");
    private static final int SEQUENTIAL = 3;
    private static final int CONCURRENT = 16;
    /** 호출 하나에 주는 최대 시간. 이걸 넘기면 Redisson이 예외 없이 무한 대기한 것이다. */
    private static final long CALL_CAP_SECONDS = 120;
    /** AttendanceService.LOCK_WAIT_SECONDS / LOCK_LEASE_SECONDS */
    private static final long LOCK_WAIT = 3, LOCK_LEASE = 3;

    @Test
    @DisplayName("Redis 정지·멈춤 시 AiQuota.consume / RLock.tryLock이 돌아오기까지 — 운영 Redisson 설정")
    void 열림_경로_대기_시간() throws Exception {
        System.out.println();
        System.out.println("=== Redis 장애 시 열림 경로 대기 시간 (ADR-0009 4절) ===");
        System.out.println("클라이언트: RedissonConfig 그대로 (retryAttempts 3, retryInterval 1500ms, timeout 3000ms, pool 8)");
        System.out.println();

        try (GenericContainer<?> redis = newRedis()) {
            redis.start();
            RedissonClient client = productionClient(redis);
            try {
                AiQuota quota = quota(client);
                section("A. 살아 있을 때 (기준)");
                sequential("consume", 50, () -> quota.consume(AiQuotaKind.CHATBOT, user()));
                sequential("tryLock", 50, () -> tryLock(client));

                redis.stop();
                section("B. 정지 — 컨테이너 제거, 포트 닫힘 (Connection refused)");
                sequential("consume", SEQUENTIAL, () -> quota.consume(AiQuotaKind.CHATBOT, user()));
                sequential("tryLock", SEQUENTIAL, () -> tryLock(client));
                concurrent("consume ×" + CONCURRENT, () -> quota.consume(AiQuotaKind.CHATBOT, user()));
            } finally {
                client.shutdown();
            }
        }

        try (GenericContainer<?> redis = newRedis()) {
            redis.start();
            RedissonClient client = productionClient(redis);
            try {
                AiQuota quota = quota(client);
                quota.consume(AiQuotaKind.CHATBOT, user()); // 연결을 하나 만들어 둔다 — 멈춤은 "살아 있던 연결"에서 겪는 것이다
                pause(redis);
                section("C. 멈춤 — docker pause, 연결은 살아 있고 응답만 없음");
                sequential("consume", SEQUENTIAL, () -> quota.consume(AiQuotaKind.CHATBOT, user()));
                sequential("tryLock", SEQUENTIAL, () -> tryLock(client));
                concurrent("consume ×" + CONCURRENT, () -> quota.consume(AiQuotaKind.CHATBOT, user()));

                unpause(redis);
                section("D. 멈춤 해제 직후 — 다시 닫히기까지");
                recovery(client);
            } finally {
                client.shutdown();
            }
        }
        System.out.println();
    }

    // ------------------------------------------------------------------

    private static GenericContainer<?> newRedis() {
        return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
    }

    /** 운영 빈을 만드는 그 메서드로 만든다. 값을 베끼면 나중에 설정이 바뀌어도 여기는 모른다. */
    private static RedissonClient productionClient(GenericContainer<?> redis) {
        RedissonConfig config = new RedissonConfig();
        ReflectionTestUtils.setField(config, "host", redis.getHost());
        ReflectionTestUtils.setField(config, "port", String.valueOf(redis.getMappedPort(6379)));
        return config.redissonClient();
    }

    private static AiQuota quota(RedissonClient client) {
        return new AiQuota(client, Clock.system(ZoneId.of("Asia/Seoul")), 1_000_000, 1_000_000, 1_000_000);
    }

    private static String user() {
        return "u-" + System.nanoTime();
    }

    /** AttendanceService.clockin의 락 부분 그대로 — 예외는 거기서처럼 잡아 폴백으로 친다. */
    private static void tryLock(RedissonClient client) {
        RLock lock = client.getLock("attendance:lock:" + user() + ":2026-09-16");
        try {
            if (lock.tryLock(LOCK_WAIT, LOCK_LEASE, TimeUnit.SECONDS)) {
                lock.unlock();
            }
        } catch (Exception e) {
            // 폴백 경로. 여기 오기까지의 시간이 재는 대상이다.
        }
    }

    private static void section(String title) {
        System.out.println("--- " + title);
    }

    private static void sequential(String label, int count, Runnable call) throws Exception {
        List<Long> samples = new ArrayList<>();
        String lastCause = "";
        for (int i = 0; i < count; i++) {
            Timed t = timed(call);
            samples.add(t.nanos);
            if (t.cause != null) lastCause = t.cause;
        }
        Collections.sort(samples);
        if (count <= SEQUENTIAL) {
            StringBuilder each = new StringBuilder();
            for (long n : samples) each.append(String.format(Locale.ROOT, "%8.0f", n / 1e6));
            System.out.printf(Locale.ROOT, "%-14s 순차 %d회, 각(ms):%s%s%n", label, count, each, lastCause);
        } else {
            System.out.printf(Locale.ROOT, "%-14s 순차 %d회, p50 %.2f ms, p99 %.2f ms, max %.2f ms%n",
                    label, count, pct(samples, 0.5), pct(samples, 0.99), samples.get(samples.size() - 1) / 1e6);
        }
    }

    private static void concurrent(String label, Runnable call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);
        try {
            List<Future<Timed>> futures = new ArrayList<>();
            long start = System.nanoTime();
            for (int i = 0; i < CONCURRENT; i++) {
                futures.add(pool.submit(() -> timed(call)));
            }
            List<Long> samples = new ArrayList<>();
            for (Future<Timed> f : futures) {
                samples.add(f.get(CALL_CAP_SECONDS, TimeUnit.SECONDS).nanos);
            }
            long wall = System.nanoTime() - start;
            Collections.sort(samples);
            System.out.printf(Locale.ROOT, "%-14s 동시 %d, 각 요청 min %.0f / p50 %.0f / max %.0f ms, 전체 %.1f s%n",
                    label, CONCURRENT, samples.get(0) / 1e6, pct(samples, 0.5),
                    samples.get(samples.size() - 1) / 1e6, wall / 1e9);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 풀린 뒤 첫 호출이 얼마나 걸리고, 언제부터 실제로 Redis에 닿는지(카운터가 세어지는지). */
    private static void recovery(RedissonClient client) throws Exception {
        long since = System.nanoTime();
        AiQuota quota = quota(client);
        String user = user();
        for (int i = 1; i <= 20; i++) {
            Timed t = timed(() -> quota.consume(AiQuotaKind.CHATBOT, user));
            long counted = client.getAtomicLong("quota:ai:chatbot:" + user + ":" + java.time.LocalDate.now(ZoneId.of("Asia/Seoul"))).get();
            boolean closed = counted > 0;
            System.out.printf(Locale.ROOT, "  호출 %2d: %7.0f ms, 해제 후 %6.2f s, %s%s%n",
                    i, t.nanos / 1e6, (System.nanoTime() - since) / 1e9,
                    closed ? "세어짐(닫힘)" : "열린 채 통과", t.cause == null ? "" : t.cause);
            if (closed) break;
            Thread.sleep(200);
        }
    }

    private record Timed(long nanos, String cause) {}

    /** 호출을 별도 스레드에서 돌려 상한을 건다. 상한을 넘기면 실패 — Redisson이 무한 대기한 것이다. */
    private static Timed timed(Runnable call) throws Exception {
        ExecutorService one = Executors.newSingleThreadExecutor();
        try {
            Callable<Timed> task = () -> {
                long start = System.nanoTime();
                try {
                    call.run();
                    return new Timed(System.nanoTime() - start, null);
                } catch (RuntimeException e) {
                    // 열림 정책이면 여기 오면 안 된다 — 왔다면 그것도 결과다.
                    return new Timed(System.nanoTime() - start, "  !! 예외가 밖으로: " + e);
                }
            };
            Future<Timed> f = one.submit(task);
            try {
                Timed t = f.get(CALL_CAP_SECONDS, TimeUnit.SECONDS);
                assertTrue(t.cause == null, "열림 정책인데 예외가 호출자에게 나왔다: " + t.cause);
                return t;
            } catch (java.util.concurrent.TimeoutException e) {
                throw new AssertionError("호출이 " + CALL_CAP_SECONDS + "초 안에 돌아오지 않았다 — 열림이 아니라 무한 대기다");
            }
        } finally {
            one.shutdownNow();
        }
    }

    private static void pause(GenericContainer<?> c) {
        DockerClientFactory.instance().client().pauseContainerCmd(c.getContainerId()).exec();
    }

    private static void unpause(GenericContainer<?> c) {
        DockerClientFactory.instance().client().unpauseContainerCmd(c.getContainerId()).exec();
    }

    private static double pct(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1))) / 1e6;
    }
}
