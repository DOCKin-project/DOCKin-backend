package com.DOCKin.ai.quota;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검증: 한도가 <b>프로세스 밖</b>에서 세어지고, 종류별로 갈리고, 자정에 풀리고, Redis가 죽으면 <b>연다</b>.
 *
 * <p>{@code JwtBlacklistRedisTest}와 같은 방식 — 스프링 없이 컨테이너의 Redis에 직접 붙는다.
 * 시계는 고정한다. 자정까지 남은 초가 응답({@code Retry-After})에 나가므로 "지금"이 흔들리면
 * 단언이 안 된다.
 */
class AiQuotaRedisTest extends ContainerTestSupport {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 2026-09-16 23:00 KST. 자정까지 3,600초. */
    private static final ZonedDateTime AT_23 = ZonedDateTime.of(2026, 9, 16, 23, 0, 0, 0, SEOUL);

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
                // 죽은 Redis 테스트가 기본값(3회 × 3초)으로 기다리지 않게. 운영 설정과 다른 값이지만
                // 여기서 재는 것은 "열리는가"이지 "얼마나 기다리는가"가 아니다.
                // connectTimeout은 넉넉히 둔다 — 갓 뜬 컨테이너에 500ms로 붙으면 Docker Desktop에서 실패한다.
                .setRetryAttempts(0)
                .setTimeout(500)
                .setConnectTimeout(3000);
        return Redisson.create(config);
    }

    private static AiQuota quota(RedissonClient client, ZonedDateTime now, long chatbot) {
        Clock fixed = Clock.fixed(now.toInstant(), now.getZone());
        return new AiQuota(client, fixed, chatbot, 100, 2000);
    }

    private static String user() {
        return "u-" + System.nanoTime();
    }

    @Test
    @DisplayName("상한까지는 통과하고 상한+1에서 429다 - 그 호출도 이미 세어졌다")
    void 상한() {
        AiQuota quota = quota(redisson, AT_23, 3);
        String user = user();

        for (int i = 0; i < 3; i++) {
            quota.consume(AiQuotaKind.CHATBOT, user);
        }

        assertThatThrownBy(() -> quota.consume(AiQuotaKind.CHATBOT, user))
                .isInstanceOf(AiQuotaExceededException.class)
                .satisfies(e -> {
                    AiQuotaExceededException ex = (AiQuotaExceededException) e;
                    assertThat(ex.getKind()).isEqualTo(AiQuotaKind.CHATBOT);
                    assertThat(ex.getRetryAfterSeconds()).isEqualTo(3600);
                    assertThat(ex.getErrorCode().getStatus()).isEqualTo(429);
                });
    }

    @Test
    @DisplayName("다른 인스턴스가 올린 것을 본다 - 재배포·다중 인스턴스에서 한도가 살아남는다")
    void 프로세스_밖() {
        AiQuota first = quota(redisson, AT_23, 1);
        AiQuota second = quota(redisson, AT_23, 1);
        String user = user();

        first.consume(AiQuotaKind.CHATBOT, user);

        assertThatThrownBy(() -> second.consume(AiQuotaKind.CHATBOT, user))
                .isInstanceOf(AiQuotaExceededException.class);
    }

    @Test
    @DisplayName("종류별로 따로 센다 - 실시간 번역을 많이 써도 챗봇이 막히지 않는다")
    void 종류별() {
        AiQuota quota = quota(redisson, AT_23, 1);
        String user = user();

        quota.consume(AiQuotaKind.CHATBOT, user);

        assertThatCode(() -> quota.consume(AiQuotaKind.RT_TRANSLATE, user)).doesNotThrowAnyException();
        assertThatCode(() -> quota.consume(AiQuotaKind.WORKLOG_TRANSLATE, user)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("사용자별로 따로 센다")
    void 사용자별() {
        AiQuota quota = quota(redisson, AT_23, 1);
        String a = user();
        String b = user();

        quota.consume(AiQuotaKind.CHATBOT, a);

        assertThatCode(() -> quota.consume(AiQuotaKind.CHATBOT, b)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("자정이 지나면 0에서 시작한다 - 키에 날짜가 있고 TTL이 자정까지다")
    void 자정() {
        String user = user();
        AiQuota today = quota(redisson, AT_23, 1);
        today.consume(AiQuotaKind.CHATBOT, user);
        assertThatThrownBy(() -> today.consume(AiQuotaKind.CHATBOT, user))
                .isInstanceOf(AiQuotaExceededException.class);

        long ttlMs = redisson.getAtomicLong("quota:ai:chatbot:" + user + ":2026-09-16").remainTimeToLive();
        assertThat(ttlMs).isBetween(Duration.ofMinutes(59).toMillis(), Duration.ofHours(1).toMillis());

        AiQuota tomorrow = quota(redisson, AT_23.plusHours(1), 1);
        assertThatCode(() -> tomorrow.consume(AiQuotaKind.CHATBOT, user)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis가 죽으면 검사 없이 통과한다 - 블랙리스트와 반대로 연다")
    void 레디스_장애_시_열림() {
        // 공유 컨테이너를 끌 수는 없으니 이 테스트만의 Redis를 하나 띄웠다가 죽인다.
        try (GenericContainer<?> dying = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)) {
            dying.start();
            RedissonClient client = connect(dying.getHost(), dying.getMappedPort(6379));
            try {
                AiQuota quota = quota(client, AT_23, 1);
                String user = user();
                quota.consume(AiQuotaKind.CHATBOT, user);

                dying.stop();

                // 상한 1을 이미 썼지만 Redis가 없으니 센 적이 없는 것처럼 지나간다.
                assertThatCode(() -> quota.consume(AiQuotaKind.CHATBOT, user)).doesNotThrowAnyException();
            } finally {
                client.shutdown();
            }
        }
    }
}
