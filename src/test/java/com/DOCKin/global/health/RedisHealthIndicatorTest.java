package com.DOCKin.global.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.api.redisnode.RedisSingle;
import org.redisson.client.RedisConnectionException;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 검증: Redis가 응답하지 않으면 health가 DOWN이다. 살아 있는 쪽은 {@code ActuatorEndpointTest}가
 * 컨테이너로 본다. 여기는 죽은 쪽 — 실제 Redis를 죽이는 대신 클라이언트가 던지는 예외를 그대로 준다.
 */
class RedisHealthIndicatorTest {

    @Test
    @DisplayName("ping이 통하면 UP")
    void up() {
        RedissonClient client = mock(RedissonClient.class);
        RedisSingle single = mock(RedisSingle.class);
        when(client.getRedisNodes(RedisNodes.SINGLE)).thenReturn(single);
        when(single.pingAll()).thenReturn(true);

        assertThat(new RedisHealthIndicator(client).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("ping에 답이 없으면 DOWN")
    void noReply() {
        RedissonClient client = mock(RedissonClient.class);
        RedisSingle single = mock(RedisSingle.class);
        when(client.getRedisNodes(RedisNodes.SINGLE)).thenReturn(single);
        when(single.pingAll()).thenReturn(false);

        assertThat(new RedisHealthIndicator(client).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("연결 예외면 DOWN이고 예외가 밖으로 새지 않는다 - health 자체가 500이 되면 안 된다")
    void connectionException() {
        RedissonClient client = mock(RedissonClient.class);
        when(client.getRedisNodes(RedisNodes.SINGLE)).thenThrow(new RedisConnectionException("connection refused"));

        var health = new RedisHealthIndicator(client).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}
