package com.DOCKin.global.security.jwt;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검증: 블랙리스트가 <b>프로세스 밖</b>에 있고, 토큰이 만료되면 <b>스스로</b> 사라진다 (P2-5, P2-18-5).
 *
 * <p>인메모리였을 때는 이 둘 다 거짓이었다 — 재시작하면 통째로 사라졌고, 청소는 한 시간짜리
 * 스케줄러가 했다. 여기서는 스프링 없이 컨테이너의 Redis에 직접 붙는다. 두 번째
 * {@code JwtBlacklist} 인스턴스가 첫 번째가 넣은 것을 보는 것이 "프로세스 밖"의 증명이다.
 */
class JwtBlacklistRedisTest extends ContainerTestSupport {

    private static RedissonClient redisson;

    @BeforeAll
    static void connectRedis() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379))
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(2);
        redisson = Redisson.create(config);
    }

    @AfterAll
    static void disconnectRedis() {
        redisson.shutdown();
    }

    @Test
    @DisplayName("다른 인스턴스가 넣은 토큰을 본다 - 재시작·다중 인스턴스에서 로그아웃이 살아남는다")
    void 프로세스_밖() {
        JwtBlacklist first = new JwtBlacklist(redisson);
        JwtBlacklist second = new JwtBlacklist(redisson);
        String token = "tok-" + System.nanoTime();

        first.add(token, System.currentTimeMillis() + 60_000);

        assertTrue(second.isBlacklisted(token));
        assertFalse(second.isBlacklisted(token + "-other"));
    }

    @Test
    @DisplayName("만료 시각이 지나면 사라진다 - 청소 스케줄러가 없어도 된다")
    void 만료_후_삭제() throws InterruptedException {
        JwtBlacklist blacklist = new JwtBlacklist(redisson);
        String token = "short-" + System.nanoTime();

        blacklist.add(token, System.currentTimeMillis() + 300);
        assertTrue(blacklist.isBlacklisted(token));

        Thread.sleep(600);
        assertFalse(blacklist.isBlacklisted(token));
    }

    @Test
    @DisplayName("이미 만료된 토큰은 넣지 않는다 - JwtUtil이 어차피 거부한다")
    void 만료된_토큰은_무시() {
        JwtBlacklist blacklist = new JwtBlacklist(redisson);
        String token = "expired-" + System.nanoTime();

        blacklist.add(token, System.currentTimeMillis() - 1);

        assertFalse(blacklist.isBlacklisted(token));
    }

    @Test
    @DisplayName("Redis 키에 토큰 원문이 없다 - 모니터링 화면에 세션이 보이면 안 된다")
    void 키는_해시() {
        JwtBlacklist blacklist = new JwtBlacklist(redisson);
        String token = "secret-" + System.nanoTime();

        blacklist.add(token, System.currentTimeMillis() + 60_000);

        boolean rawKeyExists = redisson.getKeys().getKeysStream()
                .anyMatch(k -> k.contains(token));
        assertFalse(rawKeyExists, "토큰 원문이 키에 들어 있다");
    }
}
