package com.DOCKin.global.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 이 인스턴스에 열려 있는 WebSocket 세션. 서버가 <b>먼저</b> 끊어야 할 때 쓴다.
 *
 * <h3>왜 있는가</h3>
 * STOMP의 인증·인가는 CONNECT와 SUBSCRIBE 두 순간에만 일어난다. 그 뒤에 권한이 사라져도
 * (로그아웃, 탈퇴, 방에서 내보냄) 이미 열린 소켓과 구독은 그대로 남아 메시지를 계속 받았다.
 * 끊으려면 세션을 손에 쥐고 있어야 하는데 Spring은 그 목록을 주지 않는다 — 그래서 데코레이터로 모은다.
 *
 * <h3>사용자·토큰은 세션 속성에서 읽는다</h3>
 * STOMP 헤더의 세션 속성은 이 {@link WebSocketSession#getAttributes()}와 같은 맵이다.
 * {@code StompHandler}가 CONNECT 때 {@link #USER_ID}·{@link #TOKEN_FINGERPRINT}를 넣는다.
 * STOMP 세션 ID({@code simpSessionId})도 이 세션의 ID와 같다.
 *
 * <h3>한계 — 단일 인스턴스</h3>
 * 목록은 인스턴스 로컬이다. 지금은 앱이 하나라(ADR-0002 2-4) 충분하지만, 여러 대로 늘리면 로그아웃을 받은
 * 인스턴스가 다른 인스턴스의 소켓을 못 끊는다. 그때는 채팅 브로드캐스트와 같이 Redis Pub/Sub으로 알린다(ADR-0004).
 */
@Slf4j
@Component
public class StompSessions implements WebSocketHandlerDecoratorFactory {

    public static final String USER_ID = "userId";
    public static final String TOKEN_FINGERPRINT = "tokenFingerprint";

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessions.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /** CONNECT가 끝난 세션의 사용자. 아직 CONNECT 전이거나 이 인스턴스의 세션이 아니면 비어 있다. */
    public Optional<String> userIdOf(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        return session == null ? Optional.empty()
                : Optional.ofNullable((String) session.getAttributes().get(USER_ID));
    }

    /** 이 사용자의 세션 전부를 닫는다(탈퇴). 닫은 수. */
    public int closeByUser(String userId, CloseStatus status) {
        return closeWhere(s -> userId.equals(s.getAttributes().get(USER_ID)), status);
    }

    /** 이 토큰으로 CONNECT한 세션만 닫는다(로그아웃 — 같은 사람의 다른 기기는 남는다). 닫은 수. */
    public int closeByTokenFingerprint(String fingerprint, CloseStatus status) {
        return closeWhere(s -> fingerprint.equals(s.getAttributes().get(TOKEN_FINGERPRINT)), status);
    }

    private int closeWhere(Predicate<WebSocketSession> match, CloseStatus status) {
        int closed = 0;
        for (WebSocketSession session : sessions.values()) {
            if (!match.test(session)) {
                continue;
            }
            try {
                session.close(status);
                closed++;
            } catch (IOException e) {
                // 이미 끊기는 중이면 afterConnectionClosed가 목록에서 뺀다.
                log.warn("WebSocket 세션을 닫지 못했습니다. sessionId={}, cause={}", session.getId(), e.toString());
            }
        }
        return closed;
    }
}
