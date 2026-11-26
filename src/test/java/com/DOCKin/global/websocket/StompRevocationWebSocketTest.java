package com.DOCKin.global.websocket;

import com.DOCKin.chat.dto.ChatRoomUpdateRequestDto;
import com.DOCKin.chat.service.ChatRoomService;
import com.DOCKin.global.security.jwt.JwtUtil;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.member.dto.CustomUserInfoDto;
import com.DOCKin.member.dto.LogOutRequestDto;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검증: 권한이 사라지면 <b>이미 열린</b> 연결과 구독도 거둔다.
 *
 * <p>전에는 CONNECT(서명·만료만)와 SUBSCRIBE(멤버십) 때만 봤다. 그래서
 * <ul>
 *   <li>방에서 내보낸 사람이 소켓을 끊기 전까지 그 방 메시지를 계속 받았고,</li>
 *   <li>로그아웃·탈퇴한 사람의 토큰으로 만료 전까지 새로 붙을 수 있었으며 열린 소켓도 그대로였다.</li>
 * </ul>
 * 실제 STOMP 클라이언트로 붙어서 본다. "안 받는다"는 같은 방의 남은 멤버가 <b>받은 뒤에</b> 확인한다 —
 * 아무도 안 받았는데 "안 받았다"고 하면 전파 자체가 안 된 것과 구분되지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("WebSocket - 권한이 사라지면 열린 연결·구독도 거둔다")
class StompRevocationWebSocketTest extends ContainerTestSupport {

    @LocalServerPort private int port;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private JdbcClient jdbc;
    @Autowired private ChatRoomService chatRoomService;
    @Autowired private MemberService memberService;
    @Autowired private SimpMessagingTemplate messagingTemplate;

    private final List<String> users = new ArrayList<>();
    private final List<Integer> rooms = new ArrayList<>();
    private final WebSocketStompClient client = stompClient();

    @AfterEach
    void clean() {
        client.stop();
        for (Integer r : rooms) {
            jdbc.sql("DELETE FROM chat_members WHERE room_id = :r").param("r", r).update();
            jdbc.sql("DELETE FROM chat_rooms WHERE room_id = :r").param("r", r).update();
        }
        for (String u : users) {
            jdbc.sql("DELETE FROM users WHERE user_id = :u").param("u", u).update();
        }
    }

    @Test
    @DisplayName("방에서 내보내면 그 사람의 방 구독만 사라진다 - 남은 멤버는 받고, 내보낸 사람은 소켓이 살아 있어도 못 받는다")
    void 내보낸_멤버의_구독() throws Exception {
        String owner = user("rv-owner");
        String stay = user("rv-stay");
        String kicked = user("rv-kicked");
        int roomId = room(owner, stay, kicked);

        Queue<String> stayGot = new ConcurrentLinkedQueue<>();
        Queue<String> kickedGot = new ConcurrentLinkedQueue<>();
        StompSession staySession = connect(token(stay));
        StompSession kickedSession = connect(token(kicked));
        subscribe(staySession, "/sub/chat/room/" + roomId, stayGot);
        subscribe(kickedSession, "/sub/chat/room/" + roomId, kickedGot);

        // 구독이 브로커에 올라간 것을 받은 것으로 확인한다 — SUBSCRIBE는 응답이 없다.
        assertThat(await(() -> {
            messagingTemplate.convertAndSend("/sub/chat/room/" + roomId, "before");
            return stayGot.contains("before") && kickedGot.contains("before");
        })).as("내보내기 전에는 둘 다 받는다").isTrue();

        chatRoomService.reviseChatRoom(roomId, owner, ChatRoomUpdateRequestDto.builder()
                .removeParticipantIds(List.of(kicked)).build());

        messagingTemplate.convertAndSend("/sub/chat/room/" + roomId, "after");
        assertThat(await(() -> stayGot.contains("after"))).as("남은 멤버는 받는다").isTrue();
        Thread.sleep(300); // 같은 브로커 스레드로 나가므로 이만큼이면 내보낸 쪽에도 도착했을 것이다
        assertThat(kickedGot).as("내보낸 사람은 못 받는다").doesNotContain("after");
        assertThat(kickedSession.isConnected()).as("소켓은 그대로 - 다른 방 구독까지 끊지 않는다").isTrue();
    }

    @Test
    @DisplayName("로그아웃하면 그 토큰의 소켓만 닫히고 다른 기기의 소켓은 남는다. 그 토큰으로는 다시 못 붙는다")
    void 로그아웃() throws Exception {
        String u = user("rv-logout");
        String phoneToken = token(u);
        // iat가 초 단위라 같은 초에 만들면 토큰이 같다 — 다른 기기를 흉내 내려면 초를 넘긴다.
        Thread.sleep(1_100);
        String pcToken = token(u);
        assertThat(pcToken).isNotEqualTo(phoneToken);

        StompSession phone = connect(phoneToken);
        StompSession pc = connect(pcToken);

        memberService.logout(LogOutRequestDto.builder().accessToken(phoneToken).refreshToken("none").build());

        assertThat(await(() -> !phone.isConnected())).as("로그아웃한 토큰의 소켓은 닫힌다").isTrue();
        assertThat(pc.isConnected()).as("다른 토큰의 소켓은 남는다").isTrue();
        assertThatThrownBy(() -> connect(phoneToken)).as("로그아웃한 토큰으로 CONNECT 거부")
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("탈퇴하면 그 사람의 소켓이 전부 닫히고, 남은 토큰으로 다시 못 붙는다")
    void 탈퇴() throws Exception {
        String u = user("rv-withdraw");
        String t = token(u);
        StompSession session = connect(t);

        memberService.deleteAccount(u, u);

        assertThat(await(() -> !session.isConnected())).as("탈퇴 뒤 소켓이 닫힌다").isTrue();
        assertThatThrownBy(() -> connect(t)).as("탈퇴 회원의 토큰으로 CONNECT 거부")
                .isInstanceOf(ExecutionException.class);
    }

    private String user(String prefix) {
        String id = prefix + "-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO users (user_id, created_at, language_code, name, password,
                                   remaining_leave_days, role, ship_yard_area, tts_enabled)
                VALUES (:u, now(), 'ko', 'rv', 'x', 15, 'USER', 'A', false)
                """).param("u", id).update();
        users.add(id);
        return id;
    }

    private int room(String owner, String... members) {
        int id = jdbc.sql("INSERT INTO chat_rooms (created_at, creator_id, is_group, room_name) VALUES (now(), :u, true, 'rv') RETURNING room_id")
                .param("u", owner).query(Integer.class).single();
        rooms.add(id);
        List<String> all = new ArrayList<>(List.of(members));
        all.add(0, owner);
        for (String m : all) {
            jdbc.sql("INSERT INTO chat_members (joined_at, room_id, user_id) VALUES (now(), :r, :u)")
                    .param("r", id).param("u", m).update();
        }
        return id;
    }

    private String token(String userId) {
        return "Bearer " + jwtUtil.createAccessToken(
                CustomUserInfoDto.builder().userId(userId).name(userId).password("x").role(UserRole.USER).build());
    }

    private StompSession connect(String token) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("token", token);
        return client.connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null,
                headers, new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
    }

    private static void subscribe(StompSession session, String destination, Queue<String> sink) {
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                sink.add((String) payload);
            }
        });
    }

    private static WebSocketStompClient stompClient() {
        WebSocketStompClient c = new WebSocketStompClient(new StandardWebSocketClient());
        c.setMessageConverter(new StringMessageConverter());
        return c;
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }
}
