package com.DOCKin.chat.presence;

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

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 검증: 접속 상태가 <b>프로세스 밖</b>에 있고, 세션 단위로 세어지고, 갱신이 멎으면 사라지고,
 * Redis가 죽었다 돌아오면 스스로 복구되며, 죽어 있는 동안은 <b>열린다</b>(쓰기는 삼키고 읽기는 전부 오프라인).
 *
 * <p>{@code AiQuotaRedisTest}와 같은 방식 — 스프링 없이 컨테이너의 Redis에 직접 붙는다.
 * 인스턴스 둘은 {@link Presence} 둘로 흉내 낸다. 각각 자기 세션만 로컬에 들고 같은 Redis를 본다.
 * TTL은 2초로 줄인다 — 재는 것은 "사라지는가"이지 "30초 뒤인가"가 아니다.
 */
class PresenceRedisTest extends ContainerTestSupport {

    private static final long TTL_SECONDS = 2;

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
                // 죽은 Redis 테스트가 기본값(3회 × 1.5초)으로 기다리지 않게. 대기 시간은 ADR-0009 4절에서 따로 쟀다.
                .setRetryAttempts(0)
                .setTimeout(500)
                .setConnectTimeout(3000);
        return Redisson.create(config);
    }

    private static Presence instance(RedissonClient client) {
        return new Presence(client, TTL_SECONDS);
    }

    private static String user() {
        return "u-" + System.nanoTime();
    }

    @Test
    @DisplayName("다른 인스턴스가 붙인 사용자가 보인다 - 접속 상태가 프로세스 밖에 있다")
    void 프로세스_밖() {
        Presence a = instance(redisson);
        Presence b = instance(redisson);
        String user = user();

        a.connected(user, "s1");

        assertThat(b.isOnline(user)).isTrue();
        assertThat(b.offlineAmong(List.of(user, "nobody"))).containsExactly("nobody");
    }

    @Test
    @DisplayName("세션 둘 중 하나를 끊어도 온라인이고, 둘 다 끊으면 오프라인이다 - 폰과 PC")
    void 세션_단위() {
        Presence phone = instance(redisson);
        Presence pc = instance(redisson);
        String user = user();

        phone.connected(user, "phone");
        pc.connected(user, "pc");
        phone.disconnected("phone");
        assertThat(pc.isOnline(user)).isTrue();

        pc.disconnected("pc");
        assertThat(pc.isOnline(user)).isFalse();
        // SET의 마지막 원소가 빠지면 Redis가 키를 지운다. 빈 키가 TTL까지 남아 "온라인"으로 보이면 안 된다.
        assertThat(redisson.getKeys().countExists(Presence.KEY_PREFIX + user)).isZero();
    }

    @Test
    @DisplayName("남의 인스턴스 세션 ID로 disconnected가 와도 건드리지 않는다")
    void 남의_세션() {
        Presence a = instance(redisson);
        Presence b = instance(redisson);
        String user = user();

        a.connected(user, "s1");
        b.disconnected("s1"); // b는 s1을 모른다

        assertThat(a.isOnline(user)).isTrue();
        assertThat(a.localSessionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("갱신이 멎으면 TTL 뒤 사라진다 - 인스턴스가 통째로 죽은 경우")
    void 갱신_없이_TTL() throws InterruptedException {
        Presence dead = instance(redisson);
        Presence observer = instance(redisson);
        String user = user();

        dead.connected(user, "s1");
        assertThat(observer.isOnline(user)).isTrue();

        Thread.sleep((TTL_SECONDS + 1) * 1000);

        assertThat(observer.isOnline(user)).isFalse();
    }

    @Test
    @DisplayName("갱신하면 TTL이 밀리고, 키가 사라졌어도 다시 쓴다 - Redis가 잠깐 죽었다 돌아온 경우")
    void 갱신_복구() throws InterruptedException {
        Presence alive = instance(redisson);
        String user = user();
        alive.connected(user, "s1");

        // TTL의 절반씩 두 번 — 갱신이 없다면 두 번째 뒤엔 사라졌어야 한다.
        for (int i = 0; i < 2; i++) {
            Thread.sleep(TTL_SECONDS * 1000 / 2 + 200);
            alive.refresh();
        }
        assertThat(alive.isOnline(user)).isTrue();

        // Redis가 비워졌다(장애 후 재시작, 볼륨 없는 경우 등). 다음 갱신이 되살린다.
        redisson.getKeys().delete(Presence.KEY_PREFIX + user);
        assertThat(alive.isOnline(user)).isFalse();
        alive.refresh();
        assertThat(alive.isOnline(user)).isTrue();
    }

    @Test
    @DisplayName("Redis가 죽으면 쓰기는 삼키고 읽기는 전부 오프라인이다 - CONNECT가 막히지 않고, 푸시는 전원에게")
    void 레디스_장애_시_열림() {
        try (GenericContainer<?> dying = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)) {
            dying.start();
            RedissonClient client = connect(dying.getHost(), dying.getMappedPort(6379));
            try {
                Presence presence = instance(client);
                String user = user();
                presence.connected(user, "s1");
                assertThat(presence.isOnline(user)).isTrue();

                dying.stop();

                assertThatCode(() -> presence.connected(user(), "s2")).doesNotThrowAnyException();
                assertThatCode(presence::refresh).doesNotThrowAnyException();
                assertThatCode(() -> presence.disconnected("s1")).doesNotThrowAnyException();
                assertThat(presence.isOnline(user)).isFalse();
                assertThat(presence.offlineAmong(Set.of("a", "b"))).containsExactlyInAnyOrder("a", "b");
            } finally {
                client.shutdown();
            }
        }
    }
}
