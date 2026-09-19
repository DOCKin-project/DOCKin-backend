package com.DOCKin.chat;

import com.DOCKin.global.security.jwt.JwtUtil;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.member.dto.CustomUserInfoDto;
import com.DOCKin.member.model.UserRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 측정 M1 (ADR-0008 7절): <b>메시지가 보낸 사람을 떠나 수신자에게 닿기까지</b> 얼마나 걸리는가.
 *
 * <h3>왜 재는가</h3>
 * D1이 순서를 뒤집었다 — 전에는 전파 → 비동기 저장, 지금은 저장 → 커밋 → 전파. 정합성을 얻은 대신
 * 전파가 커밋 뒤로 밀리는 만큼 지연이 붙는다. 그 값이 얼마인지 잰 적이 없었다. 이 테스트를
 * D1 이전 커밋({@code 5aeab94})과 이후에서 같은 조건으로 돌려 전/후를 만든다.
 *
 * <h3>무엇을 재는가</h3>
 * 실제 STOMP 클라이언트가 실제 WebSocket으로 붙는다. 발신자 1명이 {@code /pub/chat/message}로 보내고
 * 수신자 9명이 {@code /sub/chat/room/{id}}에서 받는다. 지연 = 수신 시각 − 발신 직전 시각(같은 JVM의
 * {@code nanoTime}이라 시계 문제가 없다). 표본은 수신자 9명 × 메시지 수다.
 *
 * <p>두 조건 — 가정표 3-3의 피크 30 msg/s로 고르게 보내는 것과, 간격 없이 몰아 보내는 것(버스트).
 * 후자는 방 행 락에 줄이 서는 조건이다.
 *
 * <h3>결과를 단정하지 않는다</h3>
 * 측정이다. 검증하는 것은 보낸 만큼 받았다는 것뿐이다. 숫자는 ADR-0008 7-1절에 옮겼다(2026-09-14).
 *
 * <h3>이전 코드와 비교할 때 — 풀 크기를 같이 봐야 한다</h3>
 * {@code 5aeab94}에서 이 파일을 돌리면 정속 p50이 초 단위로 나온다. D1 때문이 아니다 — 그 코드의
 * {@code @Async saveMessage}는 이름 없는 {@code @Async}라 {@code messageExecutor}가 아닌
 * {@code SimpleAsyncTaskExecutor}(작업당 스레드)로 돌고, 저장 스레드 수백 개가 커넥션 풀(10)을 고갈시켜
 * 인바운드 스레드의 조회가 커넥션을 기다린다. D1의 순수한 대가를 보려면 이전 코드를
 * {@code SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=100}으로 돌려 그 효과를 걷어낸 값과 비교한다.
 * 이후 코드는 풀 크기에 반응하지 않는다(동기 저장, 스레드당 커넥션 하나).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatLatencyMeasurementTest extends ContainerTestSupport {

    private static final int RECEIVERS = 9;
    private static final int WARMUP = 30;
    private static final int PACED_MESSAGES = 300;
    private static final int PACED_INTERVAL_MS = 33;     // ≈ 30 msg/s, 가정표 3-3의 피크
    private static final int BURST_MESSAGES = 300;
    private static final Pattern CONTENT = Pattern.compile("\"content\"\\s*:\\s*\"m-(\\d+)\"");

    @LocalServerPort private int port;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private JdbcClient jdbc;

    private int roomId;
    private final List<String> users = new ArrayList<>();
    private final List<StompSession> sessions = new ArrayList<>();
    private WebSocketStompClient client;

    /** 수신 시각. key = "수신자/메시지번호". 모든 수신자가 공유한다. */
    private final ConcurrentHashMap<String, Long> receivedAt = new ConcurrentHashMap<>();
    private volatile CountDownLatch pending = new CountDownLatch(0);

    @BeforeAll
    void setUp() throws Exception {
        for (int i = 0; i <= RECEIVERS; i++) {
            String u = "m1-u" + i;
            users.add(u);
            jdbc.sql("""
                    INSERT INTO users (user_id, created_at, language_code, name, password,
                                       remaining_leave_days, role, ship_yard_area, tts_enabled)
                    VALUES (:u, now(), 'ko', 'm1', 'x', 15, 'USER', 'A', false)
                    ON CONFLICT (user_id) DO NOTHING
                    """).param("u", u).update();
        }
        roomId = jdbc.sql("INSERT INTO chat_rooms (created_at, creator_id, is_group, room_name) VALUES (now(), :u, true, 'm1') RETURNING room_id")
                .param("u", users.get(0)).query(Integer.class).single();
        for (String u : users) {
            jdbc.sql("INSERT INTO chat_members (joined_at, room_id, user_id) VALUES (now() - interval '1 minute', :r, :u)")
                    .param("r", roomId).param("u", u).update();
        }

        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new SimpleMessageConverter()); // byte[] 그대로. JSON은 손으로 만든다
        client.setInboundMessageSizeLimit(1024 * 1024);

        for (int i = 0; i <= RECEIVERS; i++) {
            String u = users.get(i);
            StompHeaders connect = new StompHeaders();
            connect.add("token", "Bearer " + jwtUtil.createAccessToken(
                    CustomUserInfoDto.builder().userId(u).name(u).password("x").role(UserRole.USER).build()));
            StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null, connect,
                    new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
            sessions.add(session);
            if (i > 0) {
                final String receiver = u;
                session.subscribe("/sub/chat/room/" + roomId, new StompFrameHandler() {
                    @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
                    @Override public void handleFrame(StompHeaders headers, Object payload) {
                        long now = System.nanoTime();
                        Matcher m = CONTENT.matcher(new String((byte[]) payload, StandardCharsets.UTF_8));
                        if (m.find()) {
                            receivedAt.put(receiver + "/" + m.group(1), now);
                            pending.countDown();
                        }
                    }
                });
            }
        }
        awaitSubscriptions();
    }

    /**
     * 구독이 브로커에 등록됐는지를 <b>영수증이 아니라 실제 도착</b>으로 확인한다.
     *
     * <p>처음에는 SUBSCRIBE에 {@code receipt} 헤더를 달고 RECEIPT를 기다렸다. 오지 않았다 —
     * {@code SimpleBrokerMessageHandler}는 RECEIPT 프레임을 만들지 않는다(외부 브로커 릴레이만 만든다).
     * 그렇다고 확인 없이 보내면 SUBSCRIBE가 inbound 채널의 스레드 풀을 지나 브로커에 닿기 전에
     * 첫 메시지가 나가 표본 몇 개가 빈다 — 그건 지연이 아니라 측정 오류다.
     *
     * <p>그래서 프로브를 보낸다. 9명이 전부 받으면 그 뒤로 보내는 메시지는 빠질 수 없다.
     * 프로브 번호는 측정 범위(0~2299)와 겹치지 않게 900000부터 쓴다.
     */
    private void awaitSubscriptions() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            final int idx = 900_000 + attempt;
            receivedAt.clear();
            pending = new CountDownLatch(RECEIVERS);
            send(idx);
            pending.await(300, TimeUnit.MILLISECONDS);
            long got = users.stream().skip(1).filter(u -> receivedAt.containsKey(u + "/" + idx)).count();
            if (got == RECEIVERS) return;
            Thread.sleep(100);
        }
        fail("프로브 50번을 보내도 수신자 " + RECEIVERS + "명이 전부 받지 못했다");
    }

    @AfterAll
    void tearDown() {
        for (StompSession s : sessions) { try { s.disconnect(); } catch (Exception ignored) {} }
        if (client != null) client.stop();
        jdbc.sql("DELETE FROM chat_members WHERE room_id = :r").param("r", roomId).update();
        jdbc.sql("DELETE FROM chat_rooms WHERE room_id = :r").param("r", roomId).update();
        jdbc.sql("DELETE FROM users WHERE user_id LIKE 'm1-u%'").update();
    }

    @Test
    @DisplayName("M1: 발신 → 수신 지연 p50/p95/p99 — 30 msg/s 정속과 버스트")
    void 수신_지연_측정() throws Exception {
        System.out.println();
        System.out.println("=== M1. 발신 → 수신 지연 (ADR-0008 7절) ===");
        System.out.printf("수신자 %d명, 워밍업 %d건 제외, 표본 = 수신자 × 메시지%n", RECEIVERS, WARMUP);
        System.out.println();
        System.out.printf("%-14s %6s %8s %9s %9s %9s %9s %9s%n",
                "조건", "발신", "수신", "p50(ms)", "p95(ms)", "p99(ms)", "max(ms)", "소요(s)");

        run("워밍업", WARMUP, PACED_INTERVAL_MS, 0, false);
        run("정속 30/s", PACED_MESSAGES, PACED_INTERVAL_MS, 1000, true);
        run("버스트", BURST_MESSAGES, 0, 2000, true);

        System.out.println();
        System.out.println(">>> 같은 테스트를 D1 이전 커밋(5aeab94)과 이후에서 돌린 값의 차이가 D1의 대가다.");
        System.out.println();
    }

    // ------------------------------------------------------------------

    private void run(String label, int count, int intervalMs, int base, boolean print) throws Exception {
        receivedAt.clear();
        pending = new CountDownLatch(count * RECEIVERS);
        long[] sentAt = new long[count];

        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            int idx = base + i;
            sentAt[i] = System.nanoTime();
            send(idx);
            if (intervalMs > 0) Thread.sleep(intervalMs);
        }
        boolean all = pending.await(60, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - start;

        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            for (int r = 1; r <= RECEIVERS; r++) {
                Long at = receivedAt.get(users.get(r) + "/" + (base + i));
                if (at != null) samples.add(at - sentAt[i]);
            }
        }
        samples.sort(Long::compare);

        if (print) {
            System.out.printf(Locale.ROOT, "%-14s %6d %8d %9.2f %9.2f %9.2f %9.2f %9.1f%n",
                    label, count, samples.size() / RECEIVERS,
                    pct(samples, 0.50), pct(samples, 0.95), pct(samples, 0.99),
                    samples.isEmpty() ? 0 : samples.get(samples.size() - 1) / 1e6,
                    elapsed / 1e9);
            assertTrue(all, label + ": 60초 안에 다 받지 못했다. 받은 표본 " + samples.size());
            assertEquals(count * RECEIVERS, samples.size(), label + ": 보낸 만큼 받지 못했다");
        }
    }

    private void send(int idx) {
        String json = "{\"roomId\":" + roomId + ",\"senderId\":\"" + users.get(0)
                + "\",\"content\":\"m-" + idx + "\",\"messageType\":\"TEXT\"}";
        StompHeaders h = new StompHeaders();
        h.setDestination("/pub/chat/message");
        h.setContentType(MimeTypeUtils.APPLICATION_JSON);
        sessions.get(0).send(h, json.getBytes(StandardCharsets.UTF_8));
    }

    private static double pct(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1))) / 1e6;
    }
}
