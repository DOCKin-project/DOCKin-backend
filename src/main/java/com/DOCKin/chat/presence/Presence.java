package com.DOCKin.chat.presence;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBatch;
import org.redisson.api.RFuture;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 채팅 <b>접속 상태.</b> "이 사용자가 지금 WebSocket으로 붙어 있는가"를 Redis에 둔다.
 *
 * <h3>왜 있는가 — 읽는 곳은 아직 없고, 자리를 옮긴 것이다</h3>
 * 전에는 {@code StompHandler}의 static {@code Map<sessionId, userId>}였다. 셋이 문제였다 —
 * 아무도 읽지 않았고, 인스턴스마다 따로라 다중 인스턴스에서 남의 인스턴스 사용자는 안 보이며,
 * DISCONNECT <b>프레임</b>만 잡아서 네트워크가 끊긴 세션은 영원히 남았다.
 * 첫 소비자는 FCM(백로그 P2-12-6, ADR-0008 8절)이다 — 방 멤버 중 <b>접속 중이 아닌</b> 사람에게만 푸시한다.
 * 접속 중인 사람은 WebSocket으로 이미 받았다. 그 판단을 할 자리를 미리 만든다.
 *
 * <h3>키 — 사용자당 SET 하나, TTL 30초</h3>
 * {@code presence:{userId}} = 세션 ID의 SET. 한 사람이 폰과 PC로 동시에 붙을 수 있어 세션을 센다.
 * 세션별 키로 쪼개면 "온라인인가"가 {@code SCAN}이 돼 팬아웃마다 키스페이스를 훑는다.
 * SET은 마지막 원소가 빠지면 Redis가 키를 지우므로 비었을 때 따로 지울 것이 없다.
 *
 * <h3>갱신 — 인스턴스가 자기 세션을 10초마다 다시 쓴다</h3>
 * 각 인스턴스는 자기 세션 목록을 로컬에 들고 {@link #refresh()}에서 전부 {@code SADD + EXPIRE}한다.
 * 그래서 인스턴스가 통째로 죽으면 그 세션들은 30초 안에 사라지고, Redis가 잠깐 죽었다 돌아오면
 * 다음 갱신에서 스스로 복구된다(ADR-0009 6절 "TTL 키라 돌아오면 다음 하트비트에 복구").
 * 10초는 STOMP heartbeat({@code WebSocketConfig})와 같은 박자다.
 *
 * <h3>해제 — 프레임이 아니라 이벤트</h3>
 * {@link SessionDisconnectEvent}는 DISCONNECT 프레임뿐 아니라 전송이 끊기거나 heartbeat가 멎어
 * 서버가 세션을 닫을 때도 온다. 전 코드가 놓치던 경우다.
 *
 * <h3>Redis가 죽으면 — 열림, 읽기는 "전부 오프라인"</h3>
 * 쓰기 실패는 WARN 남기고 삼킨다. 접속 상태 때문에 CONNECT가 막힐 이유가 없다(기능 품질, ADR-0009 3절).
 * 읽기 실패는 <b>아무도 접속 중이 아니다</b>로 답한다 — FCM이 이 답을 쓰면 전원에게 푸시가 나가
 * 접속 중인 사람은 두 번 받는다. 반대로 "전부 온라인"이면 장애 동안 오프라인 사용자가 알림을 못 받는다.
 * 중복이 유실보다 낫다.
 */
@Slf4j
@Component
public class Presence {

    static final String KEY_PREFIX = "presence:";

    private final RedissonClient redissonClient;
    private final Duration ttl;

    /** 이 인스턴스의 세션. sessionId → userId. 갱신과 해제가 여기서 사용자를 찾는다. */
    private final Map<String, String> localSessions = new ConcurrentHashMap<>();

    public Presence(RedissonClient redissonClient,
                    @Value("${chat.presence.ttl-seconds:30}") long ttlSeconds) {
        this.redissonClient = redissonClient;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** CONNECT 인증이 끝난 뒤. {@code StompHandler}가 부른다. */
    public void connected(String userId, String sessionId) {
        localSessions.put(sessionId, userId);
        try {
            RSet<String> sessions = set(userId);
            sessions.add(sessionId);
            sessions.expire(ttl);
        } catch (RuntimeException e) {
            log.warn("접속 상태를 기록하지 못했습니다. 다음 갱신에서 다시 씁니다. userId={}, cause={}", userId, e.toString());
        }
    }

    /**
     * 세션이 끝났을 때 — DISCONNECT 프레임, 전송 끊김, heartbeat 실패 전부.
     * 이 인스턴스의 세션이 아니면(다른 인스턴스 것) 아무것도 하지 않는다.
     */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        disconnected(event.getSessionId());
    }

    void disconnected(String sessionId) {
        String userId = localSessions.remove(sessionId);
        if (userId == null) {
            return;
        }
        try {
            set(userId).remove(sessionId);
        } catch (RuntimeException e) {
            log.warn("접속 해제를 기록하지 못했습니다. TTL이 정리합니다. userId={}, cause={}", userId, e.toString());
        }
        log.info("WebSocket 연결 종료 - userId: {}", userId);
    }

    /** 이 사용자가 어느 인스턴스에든 붙어 있는가. Redis 장애면 {@code false}. */
    public boolean isOnline(String userId) {
        try {
            return set(userId).isExists();
        } catch (RuntimeException e) {
            log.warn("접속 상태를 읽지 못해 오프라인으로 봅니다. userId={}, cause={}", userId, e.toString());
            return false;
        }
    }

    /**
     * 주어진 사용자 중 <b>접속 중이 아닌</b> 사람. FCM이 방 멤버를 넣고 푸시 대상을 받는 꼴이다.
     * 왕복 한 번(배치). Redis 장애면 전원을 돌려준다 — 클래스 주석의 "전부 오프라인".
     */
    public Set<String> offlineAmong(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        List<String> ids = new ArrayList<>(new HashSet<>(userIds));
        try {
            RBatch batch = redissonClient.createBatch();
            List<RFuture<Boolean>> exists = new ArrayList<>(ids.size());
            for (String id : ids) {
                exists.add(batch.getSet(KEY_PREFIX + id, StringCodec.INSTANCE).isExistsAsync());
            }
            batch.execute();
            Set<String> offline = new HashSet<>();
            for (int i = 0; i < ids.size(); i++) {
                if (!exists.get(i).toCompletableFuture().join()) {
                    offline.add(ids.get(i));
                }
            }
            return offline;
        } catch (RuntimeException e) {
            log.warn("접속 상태를 읽지 못해 {}명 전원을 오프라인으로 봅니다. cause={}", ids.size(), e.toString());
            return new HashSet<>(ids);
        }
    }

    /**
     * 이 인스턴스의 세션을 전부 다시 쓴다. TTL을 미는 것이자, Redis 장애 뒤의 복구다.
     * 사용자별로 {@code SADD}(그 사용자의 세션 전부) + {@code EXPIRE} 한 번 — 배치 하나에 담아 왕복은 한 번이다.
     */
    @Scheduled(fixedDelayString = "${chat.presence.refresh-ms:10000}")
    public void refresh() {
        if (localSessions.isEmpty()) {
            return;
        }
        Map<String, List<String>> byUser = new HashMap<>();
        localSessions.forEach((sessionId, userId) ->
                byUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(sessionId));
        try {
            RBatch batch = redissonClient.createBatch();
            byUser.forEach((userId, sessionIds) -> {
                var set = batch.getSet(KEY_PREFIX + userId, StringCodec.INSTANCE);
                set.addAllAsync(sessionIds);
                set.expireAsync(ttl);
            });
            batch.execute();
        } catch (RuntimeException e) {
            log.warn("접속 상태 갱신 실패. 사용자 {}명이 TTL {}초 안에 오프라인으로 보일 수 있습니다. cause={}",
                    byUser.size(), ttl.toSeconds(), e.toString());
        }
    }

    /** 이 인스턴스에 붙어 있는 세션 수. 테스트와 로그용. */
    int localSessionCount() {
        return localSessions.size();
    }

    private RSet<String> set(String userId) {
        return redissonClient.getSet(KEY_PREFIX + userId, StringCodec.INSTANCE);
    }
}
