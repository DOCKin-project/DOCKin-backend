package com.DOCKin.chat;

import com.DOCKin.global.security.jwt.JwtUtil;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.member.dto.CustomUserInfoDto;
import com.DOCKin.member.model.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 측정 M6 (ADR-0008 7절): <b>연결이 끊긴 사이에 온 메시지를 재접속 뒤 빠짐없이 받는가.</b>
 *
 * <h3>왜 재는가</h3>
 * 서버는 {@code GET .../messages/after?seq=}를 준다(D8). 그것이 있다는 것과 클라이언트 프로토콜이
 * 실제로 유실 0을 만든다는 것은 다른 말이다. 이 테스트는 {@code chat_test.html}이 하는 일을 그대로 한다 —
 * 마지막으로 받은 {@code roomSeq}를 들고 있다가, 끊겼다 붙으면 그 뒤를 받고, 실시간 전파와 겹친 것은
 * {@code messageId}로 걸러낸다. 그리고 <b>같은 조건에서 따라잡기를 하지 않은 순진한 수신자</b>가 얼마를
 * 잃는지 옆에 놓는다. 그 차이가 이 프로토콜의 값이다.
 *
 * <h3>시나리오</h3>
 * <ol>
 *   <li>수신자가 방을 구독한 채 발신자가 {@value #BEFORE}건 → 다 받는다. 커서 = 받은 최대 roomSeq</li>
 *   <li>수신자가 소켓을 끊는다(DISCONNECT 프레임 없이 — Wi-Fi가 죽은 것과 같다). 그 사이 {@value #WHILE_GONE}건</li>
 *   <li>수신자가 다시 붙어 구독한다. <b>구독 직후</b> 발신자가 {@value #OVERLAP}건을 더 보낸다 — 따라잡기 응답과 실시간
 *       전파가 겹치는 창을 만든다</li>
 *   <li>수신자가 {@code after?seq=커서}를 {@code hasNext}가 끝날 때까지 부른다. 받은 것을 실시간 전파와 합친다</li>
 * </ol>
 * 검증: 합친 결과가 {@value #BEFORE}+{@value #WHILE_GONE}+{@value #OVERLAP}건 전부이고 roomSeq에 구멍이 없다.
 * 겹친 창에서 중복이 실제로 생겼는지(생겼다면 걸러졌는지)도 함께 적는다 — 중복이 0이면 겹침 창을 못 만든 것이지
 * 프로토콜이 좋은 것이 아니다.
 *
 * <p>따라잡기 페이지 크기를 작게({@value #PAGE_LIMIT}) 잡아 {@code hasNext} 이어 부르기도 실제로 돌게 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatReconnectCatchUpMeasurementTest extends ContainerTestSupport {

    private static final int BEFORE = 5;
    private static final int WHILE_GONE = 40;
    private static final int OVERLAP = 10;
    private static final int PAGE_LIMIT = 15;

    @LocalServerPort private int port;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper om;

    private int roomId;
    private final String sender = "m6-sender";
    private final String receiver = "m6-receiver";
    private String receiverToken;
    private WebSocketStompClient client;
    private StompSession senderSession;
    private RestClient rest;

    @BeforeAll
    void setUp() throws Exception {
        for (String u : List.of(sender, receiver)) {
            jdbc.sql("""
                    INSERT INTO users (user_id, created_at, language_code, name, password,
                                       remaining_leave_days, role, ship_yard_area, tts_enabled)
                    VALUES (:u, now(), 'ko', 'm6', 'x', 15, 'USER', 'A', false)
                    ON CONFLICT (user_id) DO NOTHING
                    """).param("u", u).update();
        }
        roomId = jdbc.sql("INSERT INTO chat_rooms (created_at, creator_id, is_group, room_name) VALUES (now(), :u, false, 'm6') RETURNING room_id")
                .param("u", sender).query(Integer.class).single();
        for (String u : List.of(sender, receiver)) {
            jdbc.sql("INSERT INTO chat_members (joined_at, room_id, user_id) VALUES (now() - interval '1 minute', :r, :u)")
                    .param("r", roomId).param("u", u).update();
        }

        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new SimpleMessageConverter());
        client.setInboundMessageSizeLimit(1024 * 1024);

        senderSession = connect(sender);
        receiverToken = token(receiver);
        rest = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + receiverToken).build();
    }

    @AfterAll
    void tearDown() {
        try { senderSession.disconnect(); } catch (Exception ignored) {}
        if (client != null) client.stop();
        jdbc.sql("DELETE FROM chat_members WHERE room_id = :r").param("r", roomId).update();
        jdbc.sql("DELETE FROM chat_rooms WHERE room_id = :r").param("r", roomId).update();
        jdbc.sql("DELETE FROM users WHERE user_id LIKE 'm6-%'").update();
    }

    @Test
    @DisplayName("M6: 끊긴 사이 40건 + 겹침 창 10건 — 커서 따라잡기는 유실 0, 순진한 수신자는 끊긴 사이를 전부 잃는다")
    void 재접속_따라잡기_유실_0() throws Exception {
        // ---- 1. 붙어 있는 동안 ----
        Receiver live = new Receiver();
        StompSession receiverSession = connect(receiver);
        subscribe(receiverSession, live);
        awaitSubscription(live);                      // 프로브가 닿을 때까지 (simple broker는 RECEIPT를 안 준다)
        live.expect(BEFORE);
        for (int i = 0; i < BEFORE; i++) send("before-" + i);
        assertTrue(live.await(10), "붙어 있는 동안 보낸 것을 다 받아야 한다");
        long cursor = live.maxSeq();                  // chat_test.html의 lastSeq — 프로브 포함, 받은 것 중 최대

        // ---- 2. 끊김 ----
        // disconnect()는 DISCONNECT 프레임을 보낸다. 소켓이 소리 없이 죽은 것과는 "서버가 알아채는 시간"이 다르지만
        // (그건 heartbeat의 몫이다 — WebSocketConfig), 알아챈 뒤의 결과는 같다: 구독이 지워지고 그 사이 것은 오지 않는다.
        // 이 테스트는 그 뒤를 잰다.
        receiverSession.disconnect();
        Thread.sleep(200);
        for (int i = 0; i < WHILE_GONE; i++) send("gone-" + i);
        // 40건이 전부 커밋될 때까지 기다린다. 처음엔 300ms를 잤는데 6건이 재접속 뒤에 도착했다 — M1이 잰 대로
        // 한 방의 저장은 약 23ms/건으로 직렬화되니 40건이면 900ms다. 시간을 추측하지 않고 DB를 본다.
        awaitCommitted("gone-%", WHILE_GONE);

        // ---- 3. 재접속 + 구독, 그리고 겹침 창 ----
        Receiver after = new Receiver();
        after.received.putAll(live.received);         // 화면에 이미 그려진 것. 중복 제거의 기준이다
        after.probes.addAll(live.probes);
        StompSession again = connect(receiver);
        subscribe(again, after);
        awaitSubscription(after);
        after.expect(OVERLAP);
        Thread overlap = new Thread(() -> { for (int i = 0; i < OVERLAP; i++) send("overlap-" + i); });
        overlap.start();

        // ---- 4. 따라잡기 — 실시간 전파가 들어오는 중에 부른다 ----
        int pages = 0, fromCatchUp = 0, dupFromCatchUp = 0;
        String sliceKeys = "";
        long seq = cursor;
        boolean hasNext = true;
        while (hasNext) {
            JsonNode slice = om.readTree(rest.get()
                    .uri("/api/chat/room/{roomId}/messages/after?seq={seq}&limit={limit}", roomId, seq, PAGE_LIMIT)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class));
            pages++;
            if (pages == 1) sliceKeys = "hasNext " + (slice.has("hasNext") ? "있음" : "없음") + ", last " + (slice.has("last") ? "있음" : "없음");
            for (JsonNode row : slice.get("content")) {
                long id = row.get("messageId").asLong();
                long rs = row.get("roomSeq").asLong();
                seq = Math.max(seq, rs);
                if (row.get("content").asText().startsWith("probe-")) after.probes.add(id);
                if (after.received.putIfAbsent(id, rs) == null) fromCatchUp++; else dupFromCatchUp++;
            }
            // Slice의 JSON에는 hasNext가 없다 — last(= !hasNext)로 읽는다. 둘 다 있으면 둘 다 본다. chat_test.html도 같게 한다
            hasNext = (slice.hasNonNull("hasNext") && slice.get("hasNext").asBoolean())
                    || (slice.hasNonNull("last") && !slice.get("last").asBoolean());
            if (slice.get("content").isEmpty()) hasNext = false;
        }
        overlap.join();
        assertTrue(after.await(10), "겹침 창의 실시간 전파를 다 받아야 한다");

        // ---- 검증 ----
        int total = BEFORE + WHILE_GONE + OVERLAP;
        long mergedReal = after.countReal();                         // 프로브를 뺀 진짜 메시지
        List<Long> seqs = new ArrayList<>(after.received.values());  // 구멍 검사는 프로브 포함 — seq는 프로브도 차지한다
        seqs.sort(Long::compare);

        long naiveGot = live.countReal() + after.liveOnly.get();     // 따라잡기 없이 실시간 전파로만 받았을 것
        int liveDup = after.liveDup.get();

        System.out.println();
        System.out.println("=== M6. 재접속 따라잡기 (ADR-0008 7절) ===");
        System.out.printf("방 1개, 발신자 1, 수신자 1. 붙어서 %d건 → 끊김 → 끊긴 사이 %d건 → 재접속·구독 → 겹침 창 %d건 + 따라잡기(limit %d)%n",
                BEFORE, WHILE_GONE, OVERLAP, PAGE_LIMIT);
        System.out.printf("%-34s %s%n", "보낸 것", total + "건");
        System.out.printf("%-34s %d건 (유실 %d건 = 끊긴 사이 %d건 %s)%n", "순진한 수신자 (따라잡기 없음)",
                naiveGot, total - naiveGot, WHILE_GONE, total - naiveGot == WHILE_GONE ? "그대로" : "?");
        System.out.printf("%-34s %d건 (유실 %d건)%n", "커서 따라잡기 + messageId 중복 제거", mergedReal, total - mergedReal);
        System.out.printf("%-34s 페이지 %d, 새로 %d건, 이미 있던 것 %d건 (Slice JSON 키: %s)%n", "따라잡기 응답", pages, fromCatchUp, dupFromCatchUp, sliceKeys);
        System.out.printf("%-34s 새로 %d건, 이미 있던 것 %d건%n", "겹침 창 실시간 전파", after.liveOnly.get(), liveDup);
        System.out.printf("%-34s %d (전체 중복 %d건 — 0이면 겹침 창이 안 만들어진 것)%n", "겹침 창에서 걸러낸 중복",
                liveDup + dupFromCatchUp, liveDup + dupFromCatchUp);
        System.out.printf("%-34s %d..%d (프로브 %d건 포함), 구멍 %s%n", "roomSeq 범위", seqs.get(0), seqs.get(seqs.size() - 1),
                after.probes.size(), seqs.get(seqs.size() - 1) - seqs.get(0) + 1 == seqs.size() ? "없음" : "있음");
        System.out.println();

        assertEquals(total, mergedReal, "따라잡기 + 중복 제거 뒤에는 보낸 것 전부가 있어야 한다");
        assertEquals(seqs.size(), seqs.get(seqs.size() - 1) - seqs.get(0) + 1, "roomSeq에 구멍이 없어야 한다");
        assertEquals(WHILE_GONE, total - naiveGot, "순진한 수신자는 끊긴 사이만큼 잃는다 — 이 테스트가 끊김을 실제로 만들었다는 증거");
        assertTrue(pages >= 2, "hasNext 이어 부르기가 실제로 돌아야 한다 (limit " + PAGE_LIMIT + " < " + (WHILE_GONE + OVERLAP) + ")");
        assertFalse(after.probes.isEmpty(), "프로브가 최소 1건은 있어야 한다");

        again.disconnect();
    }

    // ------------------------------------------------------------------

    /** 수신자 한 사람의 화면. messageId → roomSeq. 실시간 전파는 여기로 들어오고, 이미 있으면 중복으로 센다. */
    private final class Receiver implements StompFrameHandler {
        final ConcurrentHashMap<Long, Long> received = new ConcurrentHashMap<>();   // 프로브 포함 — 프로브도 방의 seq를 차지하는 진짜 메시지다
        final Set<Long> probes = ConcurrentHashMap.newKeySet();
        final AtomicInteger liveOnly = new AtomicInteger();
        final AtomicInteger liveDup = new AtomicInteger();
        volatile CountDownLatch latch = new CountDownLatch(0);

        void expect(int n) { latch = new CountDownLatch(n); }
        boolean await(int seconds) throws InterruptedException { return latch.await(seconds, TimeUnit.SECONDS); }
        long maxSeq() { return received.values().stream().mapToLong(Long::longValue).max().orElse(0); }
        long countReal() { return received.keySet().stream().filter(id -> !probes.contains(id)).count(); }

        @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
        @Override public void handleFrame(StompHeaders headers, Object payload) {
            try {
                JsonNode n = om.readTree((byte[]) payload);
                long id = n.get("messageId").asLong();
                long rs = n.get("roomSeq").asLong();
                if (n.get("content").asText().startsWith("probe-")) { probes.add(id); received.putIfAbsent(id, rs); latch.countDown(); return; }
                if (received.putIfAbsent(id, rs) == null) liveOnly.incrementAndGet(); else liveDup.incrementAndGet();
                latch.countDown();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    private StompSession connect(String user) throws Exception {
        StompHeaders connect = new StompHeaders();
        connect.add("token", "Bearer " + token(user));
        return client.connectAsync("ws://localhost:" + port + "/ws", (WebSocketHttpHeaders) null, connect,
                new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
    }

    private String token(String user) {
        return jwtUtil.createAccessToken(CustomUserInfoDto.builder().userId(user).name(user).password("x").role(UserRole.USER).build());
    }

    private void subscribe(StompSession session, Receiver r) {
        session.subscribe("/sub/chat/room/" + roomId, r);
    }

    /** 구독이 브로커에 닿았는지를 프로브 도착으로 확인한다. M1과 같은 이유 — simple broker는 RECEIPT를 만들지 않는다. */
    private void awaitSubscription(Receiver r) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            r.expect(1);
            send("probe-" + attempt);
            if (r.await(1)) return;
        }
        throw new AssertionError("프로브 50번을 보내도 구독이 확인되지 않았다");
    }

    /** 같은 방에 content 패턴이 n건 커밋될 때까지 폴링한다. 다른 커넥션에서 보는 것이라 커밋된 것만 보인다. */
    private void awaitCommitted(String contentLike, int n) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer c = jdbc.sql("SELECT count(*) FROM chat_messages WHERE room_id = :r AND content LIKE :c")
                    .param("r", roomId).param("c", contentLike).query(Integer.class).single();
            if (c >= n) return;
            Thread.sleep(100);
        }
        throw new AssertionError(contentLike + " " + n + "건이 10초 안에 커밋되지 않았다");
    }

    private void send(String content) {
        String json = "{\"roomId\":" + roomId + ",\"senderId\":\"" + sender + "\",\"content\":\"" + content + "\",\"messageType\":\"TEXT\"}";
        StompHeaders h = new StompHeaders();
        h.setDestination("/pub/chat/message");
        h.setContentType(MimeTypeUtils.APPLICATION_JSON);
        senderSession.send(h, json.getBytes(StandardCharsets.UTF_8));
    }
}
