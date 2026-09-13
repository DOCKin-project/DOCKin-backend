package com.DOCKin.global.security.jwt;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * 로그아웃한 액세스 토큰. <b>Redis에 있고, 토큰이 만료되면 함께 사라진다.</b>
 *
 * <h3>왜 인메모리가 아닌가 (백로그 P2-5, P2-18-5)</h3>
 * 이전에는 {@code ConcurrentHashMap}이었다. 재배포하면 통째로 사라져 <b>로그아웃했던 토큰이
 * 만료 전까지 되살아났다.</b> 이 서비스는 refresh 토큰을 저장만 하고 갱신 경로가 없어
 * 액세스 토큰이 곧 세션이고, 그러면 블랙리스트가 유일한 폐기 수단이다 — 유일한 수단이
 * 재시작에 날아가면 로그아웃은 없는 기능이다. Redis는 분산락으로 이미 있으므로 저장소를
 * 새로 들이지 않는다.
 *
 * <h3>키는 토큰의 해시다</h3>
 * 토큰 원문을 Redis 키로 두면 {@code KEYS *}나 모니터링 화면에 세션이 그대로 보인다.
 * {@code JwtAuthFilter}가 로그에 토큰을 남기지 않기로 한 것과 같은 이유로 SHA-256만 둔다.
 * 조회는 같은 해시로 하므로 원문이 필요한 곳이 없다.
 *
 * <h3>TTL이 청소다</h3>
 * 만료 시각까지만 산다. 이전의 {@code @Scheduled cleanup}이 하던 일을 Redis가 대신하므로
 * 스케줄러가 없다. 이미 만료된 토큰은 넣지 않는다 — {@code JwtUtil.isValidToken}이 어차피
 * 거부하므로 기억할 이유가 없다.
 *
 * <h3>Redis가 죽으면 인증이 막힌다 — 의도다</h3>
 * {@code JwtAuthFilter}는 이 클래스가 던진 예외를 잡아 인증을 세우지 않는다(401).
 * ADR-0001의 분산락은 "Redis가 죽어도 DB가 방어선"이라 열어 두었지만, 여기는 열어 두면
 * "로그아웃한 토큰이 통한다"가 된다. 막는 쪽이 맞다. {@code RedissonConfig}가 기동 시
 * Redis에 붙지 못하면 앱이 뜨지 않으므로, Redis는 이미 이 서비스의 하드 의존성이다.
 */
@Component
@RequiredArgsConstructor
public class JwtBlacklist {

    private static final String KEY_PREFIX = "jwt:blacklist:";

    private final RedissonClient redissonClient;

    /**
     * @param token          액세스 토큰 원문 ({@code Bearer } 없이)
     * @param expirationMillis 토큰의 만료 시각 (epoch ms). 이 시각까지만 기억한다
     */
    public void add(String token, Long expirationMillis) {
        long ttl = expirationMillis - System.currentTimeMillis();
        if (ttl <= 0) {
            return;
        }
        bucket(token).set("1", ttl, TimeUnit.MILLISECONDS);
    }

    public boolean isBlacklisted(String token) {
        return bucket(token).isExists();
    }

    private RBucket<String> bucket(String token) {
        return redissonClient.getBucket(KEY_PREFIX + sha256(token));
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JVM 필수 알고리즘이라 일어나지 않는다.
            throw new IllegalStateException(e);
        }
    }
}
