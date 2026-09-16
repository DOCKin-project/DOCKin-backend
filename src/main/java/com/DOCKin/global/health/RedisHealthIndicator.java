package com.DOCKin.global.health;

import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNodes;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/health}의 {@code components.redis}.
 *
 * <p><b>없었다.</b> {@code ActuatorEndpointTest}의 주석은 "db·redis·diskSpace"라 했지만 실제 응답에는
 * {@code db}·{@code diskSpace}·{@code ping}뿐이었다(2026-09-16 확인). Spring Boot의 Redis 헬스 자동 설정은
 * spring-data-redis의 {@code RedisConnectionFactory}에 붙는데 이 저장소는 {@code redisson} 단독이라
 * 그 빈이 없다. 즉 Redis가 죽어도 health는 UP이었고, 블랙리스트가 닫힘 정책(ADR-0009 2절)이라
 * <b>그 장애를 전원 401로만 알 수 있었다.</b>
 *
 * <p>빈 이름 {@code redisHealthIndicator}에서 {@code HealthIndicator}를 뗀 {@code redis}가 항목 이름이다.
 *
 * <h3>Redis가 죽으면 앱 전체가 DOWN이다 — 의도다</h3>
 * 블랙리스트가 닫히면 인증이 전부 401이라 앱은 익명 경로밖에 못 받는다. 그 상태를 UP이라 하면
 * compose 헬스체크와 로드밸런서가 멀쩡한 서버로 본다. 락·한도는 열림이지만 인증이 막힌 앱에
 * 그 둘이 열려 있는 것은 의미가 없다.
 */
@Component
public class RedisHealthIndicator implements HealthIndicator {

    private final RedissonClient redissonClient;

    public RedisHealthIndicator(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public Health health() {
        try {
            boolean up = redissonClient.getRedisNodes(RedisNodes.SINGLE).pingAll();
            return up ? Health.up().build() : Health.down().withDetail("ping", "no reply").build();
        } catch (RuntimeException e) {
            return Health.down(e).build();
        }
    }
}
