package com.DOCKin.member.login;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검증: 로그인 실패가 <b>프로세스 밖</b>에서 세어지고, 상한에서 막히고, 창이 지나면 풀리고, 성공하면 지워지고,
 * Redis가 죽으면 <b>닫는다</b> (ADR-0009 여섯째 용도).
 *
 * <p>{@code AiQuotaRedisTest}와 같은 방식 — 스프링 없이 컨테이너의 Redis에 직접 붙는다. 창은 짧게(초 단위) 줘서
 * 만료를 기다려 본다. 닫힘 경로는 이 테스트만의 Redis를 띄웠다가 죽인다.
 */
class LoginAttemptsRedisTest extends ContainerTestSupport {

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
                // 죽은 Redis 테스트가 기본값(3회 × 3초)으로 기다리지 않게. 재는 것은 "닫히는가"다.
                .setRetryAttempts(0)
                .setTimeout(500)
                .setConnectTimeout(3000);
        return Redisson.create(config);
    }

    private static String user() {
        return "u-" + System.nanoTime();
    }

    private static void fail(LoginAttempts attempts, String user, int times) {
        for (int i = 0; i < times; i++) {
            attempts.check(user);
            attempts.failed(user);
        }
    }

    @Test
    @DisplayName("상한까지는 시도할 수 있고, 상한에 닿으면 다음 check가 429다 - 맞는 비밀번호도 여기서 막힌다")
    void 상한() {
        LoginAttempts attempts = new LoginAttempts(redisson, 3, 10);
        String user = user();

        fail(attempts, user, 3);

        assertThatThrownBy(() -> attempts.check(user))
                .isInstanceOf(LoginAttemptsExceededException.class)
                .satisfies(e -> {
                    LoginAttemptsExceededException ex = (LoginAttemptsExceededException) e;
                    assertThat(ex.getErrorCode().getStatus()).isEqualTo(429);
                    // 창 10분. 방금 시작했으니 거의 다 남았고, 0이면 안 된다(바로 재시도하게 된다).
                    assertThat(ex.getRetryAfterSeconds()).isBetween(590L, 600L);
                });
    }

    @Test
    @DisplayName("다른 인스턴스의 실패를 본다 - 재배포·다중 인스턴스에서 카운터가 살아남는다")
    void 프로세스_밖() {
        LoginAttempts first = new LoginAttempts(redisson, 1, 10);
        LoginAttempts second = new LoginAttempts(redisson, 1, 10);
        String user = user();

        fail(first, user, 1);

        assertThatThrownBy(() -> second.check(user)).isInstanceOf(LoginAttemptsExceededException.class);
    }

    @Test
    @DisplayName("사원번호별로 따로 센다 - 없는 사원번호도 같은 방식으로 센다")
    void 사원번호별() {
        LoginAttempts attempts = new LoginAttempts(redisson, 1, 10);
        String a = user();
        String nobody = "no-such-" + System.nanoTime();

        fail(attempts, a, 1);
        fail(attempts, nobody, 1);

        assertThatThrownBy(() -> attempts.check(a)).isInstanceOf(LoginAttemptsExceededException.class);
        // 없는 사원번호가 안 막히면 "막히는 쪽이 있는 계정"이라 열거가 열린다(P2-18-8).
        assertThatThrownBy(() -> attempts.check(nobody)).isInstanceOf(LoginAttemptsExceededException.class);
        assertThatCode(() -> attempts.check(user())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("성공하면 창 안의 실패가 지워진다")
    void 성공_시_초기화() {
        LoginAttempts attempts = new LoginAttempts(redisson, 3, 10);
        String user = user();

        fail(attempts, user, 2);
        attempts.succeeded(user);
        fail(attempts, user, 2);

        assertThatCode(() -> attempts.check(user)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("창이 지나면 풀린다 - 첫 실패가 TTL을 걸고 이후 실패는 TTL을 밀지 않는다")
    void 창_만료() throws InterruptedException {
        LoginAttempts attempts = new LoginAttempts(redisson, 2, 10);
        String user = user();

        // 창을 초 단위로 줄 방법이 없으니(분 단위 설정) TTL을 직접 줄인다 -- 보는 것은 "키가 사라지면 풀린다"다.
        fail(attempts, user, 1);
        redisson.getAtomicLong("login:fail:" + user).expire(Duration.ofMillis(800));
        fail(attempts, user, 1);
        assertThatThrownBy(() -> attempts.check(user)).isInstanceOf(LoginAttemptsExceededException.class);
        // 둘째 실패가 TTL을 다시 걸었다면 10분이 됐을 것이다.
        assertThat(redisson.getAtomicLong("login:fail:" + user).remainTimeToLive()).isLessThan(1_000L);

        Thread.sleep(1_000);

        assertThatCode(() -> attempts.check(user)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis가 죽으면 503이다 - AiQuota와 반대로 닫는다. 어차피 블랙리스트가 닫혀 토큰을 쓸 데가 없다")
    void 레디스_장애_시_닫힘() {
        try (GenericContainer<?> dying = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)) {
            dying.start();
            RedissonClient client = connect(dying.getHost(), dying.getMappedPort(6379));
            try {
                LoginAttempts attempts = new LoginAttempts(client, 10, 10);
                String user = user();
                assertThatCode(() -> attempts.check(user)).doesNotThrowAnyException();

                dying.stop();

                for (Runnable call : new Runnable[] {
                        () -> attempts.check(user), () -> attempts.failed(user), () -> attempts.succeeded(user)}) {
                    assertThatThrownBy(call::run)
                            .isInstanceOf(BusinessException.class)
                            .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                                    .isEqualTo(ErrorCode.LOGIN_UNAVAILABLE));
                }
            } finally {
                client.shutdown();
            }
        }
    }
}
