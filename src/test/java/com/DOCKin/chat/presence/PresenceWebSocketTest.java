package com.DOCKin.chat.presence;

import com.DOCKin.global.security.jwt.JwtUtil;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.member.dto.CustomUserInfoDto;
import com.DOCKin.member.model.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검증: 실제 STOMP CONNECT가 접속 상태를 만들고, <b>DISCONNECT 프레임 없이</b> 소켓만 닫혀도 지워진다.
 *
 * <p>후자가 이 클래스의 존재 이유다. 전 코드({@code StompHandler}의 static Map)는 DISCONNECT 프레임만 잡았다 —
 * 앱이 죽거나 터널이 끊기면 프레임은 안 오고 세션은 영원히 "접속 중"이었다.
 * {@code SessionDisconnectEvent}는 전송이 닫힐 때도 오므로 그 경우를 잡는다. 여기서는 STOMP 클라이언트를
 * 거치지 않고 소켓에 CONNECT 프레임을 직접 쓴 뒤 <b>소켓만</b> 닫아 그 조건을 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PresenceWebSocketTest extends ContainerTestSupport {

    @LocalServerPort private int port;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private Presence presence;

    private String token(String userId) {
        return "Bearer " + jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().userId(userId).name(userId).password("x").role(UserRole.USER).build());
    }

    @Test
    @DisplayName("STOMP로 붙으면 온라인, 곱게 끊으면(DISCONNECT 프레임) 오프라인")
    void 곱게_끊기() throws Exception {
        String user = "presence-graceful-" + System.nanoTime();
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        StompHeaders connect = new StompHeaders();
        connect.add("token", token(user));
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null,
                connect, new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
        try {
            assertThat(presence.isOnline(user)).isTrue();
        } finally {
            session.disconnect();
        }
        assertThat(await(() -> !presence.isOnline(user))).as("DISCONNECT 뒤 오프라인").isTrue();
        client.stop();
    }

    @Test
    @DisplayName("DISCONNECT 프레임 없이 소켓만 닫혀도 오프라인 - 전 코드가 놓치던 경우")
    void 소켓만_닫힘() throws Exception {
        String user = "presence-abrupt-" + System.nanoTime();
        CountDownLatch connectedFrame = new CountDownLatch(1);
        WebSocketSession socket = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                if (message.getPayload().startsWith("CONNECTED")) {
                    connectedFrame.countDown();
                }
            }
        }, "ws://localhost:" + port + "/ws").get(10, TimeUnit.SECONDS);

        // STOMP CONNECT 프레임을 손으로 쓴다. heart-beat 0,0 — 여기서 재는 건 소켓 닫힘이지 heartbeat가 아니다.
        socket.sendMessage(new TextMessage(
                "CONNECT\naccept-version:1.2\nheart-beat:0,0\ntoken:" + token(user) + "\n\n\0"));
        assertThat(connectedFrame.await(10, TimeUnit.SECONDS)).as("CONNECTED 프레임").isTrue();
        assertThat(await(() -> presence.isOnline(user))).as("CONNECT 뒤 온라인").isTrue();

        socket.close(); // DISCONNECT 프레임 없이 전송만 닫는다

        assertThat(await(() -> !presence.isOnline(user))).as("소켓 닫힘 뒤 오프라인").isTrue();
    }

    /** 이벤트는 비동기라 잠깐 기다린다. 5초면 넉넉하다 — 이벤트는 ms 단위이고 TTL(30초)로 사라지는 것이 아니다. */
    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }
}
