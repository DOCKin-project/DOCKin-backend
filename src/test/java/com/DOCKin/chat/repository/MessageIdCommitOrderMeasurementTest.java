package com.DOCKin.chat.repository;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 측정 M3 (ADR-0008 5-4, 백로그 P2-12-7): <b>{@code message_id} 순서와 커밋 순서가 실제로 어긋나는가.</b>
 *
 * <h3>왜 재는가</h3>
 * {@code chat_messages.message_id}는 {@code IDENTITY}다. 값은 INSERT 시점에 시퀀스에서 나오고
 * 트랜잭션은 그 뒤에 커밋된다. 그 사이에 다른 트랜잭션이 더 큰 ID를 받아 <b>먼저 커밋</b>하면,
 * "마지막으로 본 ID보다 큰 것"을 따라잡는 커서는 나중에 커밋된 작은 ID를 영영 못 본다.
 * ADR-0008은 읽음·재접속 기준을 ID로 옮기기로 했으므로 이 구멍이 실재하는지가 D8의 결정 변수다.
 *
 * <h3>무엇을 흉내 내는가</h3>
 * {@code ChatService.saveMessage}의 트랜잭션 모양 그대로다 — INSERT 뒤에 요약 컬럼 UPDATE 둘
 * ({@code chat_members.last_read_time}, {@code chat_rooms.last_message_*})이 있고 그 뒤 커밋이다.
 * 두 번째 UPDATE는 <b>같은 방의 같은 행</b>을 갱신하므로 동시 트랜잭션이 여기서 줄을 선다.
 * ID는 줄을 서기 <b>전에</b> 받았으므로, 줄의 순서가 ID 순서와 다르면 그대로 역전이다.
 * 비교를 위해 INSERT만 하고 커밋하는 변형도 함께 잰다 — 역전이 UPDATE의 행 락 때문인지
 * IDENTITY 자체 때문인지 갈라야 대응이 달라진다.
 *
 * <h3>두 지표</h3>
 * <ul>
 *   <li><b>역전</b>: 커밋 완료 순서로 늘어놓았을 때 자기보다 큰 ID가 이미 커밋된 뒤 커밋된 건수.
 *       커밋 완료 시각은 {@code commit()}이 돌아온 JVM 시각이라 근사치다</li>
 *   <li><b>순진한 커서 유실</b>: 별도 커넥션이 {@code message_id > cursor}로 쉬지 않고 읽으며
 *       {@code cursor = max(seen)}을 올린다 — 재접속 따라잡기 그대로다. 끝났을 때 한 번도
 *       못 본 ID가 유실이다. 이쪽은 근사가 아니라 <b>실제로 못 받은 것</b>이다</li>
 * </ul>
 *
 * <h3>결과를 단정하지 않는다</h3>
 * 이 테스트는 측정이며 결과가 0이어도 실패하지 않는다. 검증하는 것은 모든 INSERT가 커밋됐다는
 * 사실뿐이다. 숫자는 {@code docs/adr/0008} 7절과 {@code SERVICE-SCALE-ASSUMPTIONS.md}에 옮긴다.
 *
 * <h3>왜 raw JDBC인가</h3>
 * {@code ChatService}를 그대로 부르면 {@code @Async}와 스프링 컨텍스트가 끼어 무엇을 쟀는지
 * 흐려진다. 트랜잭션의 모양만 필요하므로 {@code FlywayMigrationTest}처럼 스크래치 DB에
 * 운영 마이그레이션을 적용하고 SQL을 직접 친다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageIdCommitOrderMeasurementTest extends ContainerTestSupport {

    private static final String ADMIN_DB = "postgres";
    private static final String SCRATCH_DB = "dockindb_m3_commit_order";

    /** 동시 발신자 수. 가정표 3-3의 피크 30 msg/s를 "30명이 동시에"로 읽었고, 그 위아래를 본다. */
    private static final int[] WRITER_COUNTS = {10, 30, 60};
    /** 발신자 1명이 연달아 보내는 건수. */
    private static final int MESSAGES_PER_WRITER = 50;

    @BeforeAll
    void createScratchDatabase() throws SQLException {
        try (Connection admin = DriverManager.getConnection(jdbcUrlFor(ADMIN_DB), username(), password());
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCRATCH_DB);
            st.execute("CREATE DATABASE " + SCRATCH_DB);
        }
        Flyway.configure()
                .dataSource(jdbcUrlFor(SCRATCH_DB), username(), password())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        // ROOM_SEQ 변형이 쓰는 컬럼. 운영에는 없고(V6 예정) 스크래치 DB에만 더한다.
        try (Connection c = connect(SCRATCH_DB); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE chat_rooms ADD COLUMN last_message_seq bigint NOT NULL DEFAULT 0");
            st.execute("ALTER TABLE chat_messages ADD COLUMN room_seq bigint");
            st.execute("CREATE INDEX idx_m3_room_seq ON chat_messages (room_id, room_seq)");
        }
    }

    @AfterAll
    void dropScratchDatabase() throws SQLException {
        try (Connection admin = DriverManager.getConnection(jdbcUrlFor(ADMIN_DB), username(), password());
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCRATCH_DB);
        }
    }

    @Test
    @DisplayName("M3: IDENTITY 발급 순서와 커밋 순서가 어긋나는 빈도 — INSERT만 vs saveMessage 모양")
    void 커밋_순서_역전_측정() throws Exception {
        System.out.println();
        System.out.println("=== M3. message_id 순서 vs 커밋 순서 (ADR-0008 5-4) ===");
        System.out.printf("발신자당 %d건, 순진한 커서 리더는 sleep 없이 폴링%n", MESSAGES_PER_WRITER);
        System.out.println();
        System.out.printf("%-22s %6s %8s %10s %8s %10s %8s%n",
                "변형", "발신자", "총건수", "역전(건)", "역전%", "커서유실", "유실%");

        for (Shape shape : Shape.values()) {
            for (int writers : WRITER_COUNTS) {
                Result r = run(shape, writers);
                System.out.printf("%-22s %6d %8d %10d %7.2f%% %10d %7.2f%%%n",
                        shape.label, writers, r.total,
                        r.inversions, 100.0 * r.inversions / r.total,
                        r.lostByCursor, 100.0 * r.lostByCursor / r.total);
                assertEquals(writers * MESSAGES_PER_WRITER, r.total, "커밋된 건수가 보낸 건수와 다르다");
            }
        }
        System.out.println();
        System.out.println(">>> 역전이 0이 아니면 P2-12-7의 구멍은 실재한다. 커서유실이 그중 실제로 못 받은 수다.");
        System.out.println(">>> INSERT만에서도 나오면 IDENTITY 자체의 문제고, saveMessage 모양에서만 나오면 행 락이 증폭한 것이다.");
        System.out.println(">>> 방 시퀀스(락 안)가 0이면, 순서를 깨뜨린 그 행 락이 순서를 고치는 자리라는 뜻이다.");
        System.out.println();
    }

    // ------------------------------------------------------------------

    /** 트랜잭션의 모양. 실제 코드는 SAVE_MESSAGE 쪽이다. */
    private enum Shape {
        INSERT_ONLY("INSERT만"),
        SAVE_MESSAGE("saveMessage 모양"),
        /** ADR-0008 D8 후보: 이미 잡는 방 행 락 안에서 방 단위 시퀀스를 받아 그 순서로 읽는다. */
        ROOM_SEQ("방 시퀀스(락 안)");

        final String label;
        Shape(String label) { this.label = label; }
    }

    private record Committed(long id, long commitNanos) {}

    private record Result(int total, long inversions, long lostByCursor) {}

    private Result run(Shape shape, int writers) throws Exception {
        int roomId = newRoom(writers);
        long startId = currentMaxId(roomId);

        List<Committed> committed = java.util.Collections.synchronizedList(new ArrayList<>());
        Set<Long> seenByCursor = new HashSet<>();
        AtomicBoolean writersDone = new AtomicBoolean(false);
        CountDownLatch go = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        try {
            // 순진한 커서 리더 — 재접속 따라잡기 그대로: message_id > cursor, cursor = max(seen).
            // ROOM_SEQ에서는 커서 축이 message_id가 아니라 room_seq다. 유실 집계는 message_id로 한다.
            String col = shape == Shape.ROOM_SEQ ? "room_seq" : "message_id";
            Future<?> reader = pool.submit(() -> {
                try (Connection c = connect(SCRATCH_DB);
                     PreparedStatement ps = c.prepareStatement(
                             "SELECT message_id, " + col + " FROM chat_messages WHERE room_id = ? AND " + col + " > ? ORDER BY " + col)) {
                    long cursor = shape == Shape.ROOM_SEQ ? 0 : startId;
                    go.await();
                    while (true) {
                        boolean done = writersDone.get(); // 종료 판정을 조회보다 먼저 읽어 마지막 한 바퀴를 보장
                        ps.setInt(1, roomId);
                        ps.setLong(2, cursor);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                seenByCursor.add(rs.getLong(1));
                                long key = rs.getLong(2);
                                if (key > cursor) cursor = key;
                            }
                        }
                        if (done) return null;
                    }
                }
            });

            List<Future<?>> tasks = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                String sender = "u" + w;
                tasks.add(pool.submit(() -> {
                    try (Connection c = connect(SCRATCH_DB)) {
                        c.setAutoCommit(false);
                        go.await();
                        for (int i = 0; i < MESSAGES_PER_WRITER; i++) {
                            long id;
                            if (shape == Shape.ROOM_SEQ) {
                                // 락을 먼저 잡고 번호를 받은 뒤 INSERT. 락 순서 = 커밋 순서 = seq 순서.
                                long seq = nextRoomSeq(c, roomId);
                                id = insert(c, roomId, sender, seq);
                                touchMember(c, roomId, sender);
                            } else {
                                id = insert(c, roomId, sender, null);
                                if (shape == Shape.SAVE_MESSAGE) {
                                    touchMember(c, roomId, sender);
                                    touchRoom(c, roomId);
                                }
                            }
                            c.commit();
                            committed.add(new Committed(id, System.nanoTime()));
                        }
                    }
                    return null;
                }));
            }

            go.countDown();
            for (Future<?> t : tasks) t.get();
            writersDone.set(true);
            reader.get();
        } finally {
            pool.shutdownNow();
        }

        // 역전: 커밋 완료 순으로 보며, 지금까지의 최대 ID보다 작은 ID가 커밋되면 역전.
        List<Committed> byCommit = new ArrayList<>(committed);
        byCommit.sort(Comparator.comparingLong(Committed::commitNanos));
        long maxSoFar = Long.MIN_VALUE;
        long inversions = 0;
        for (Committed cm : byCommit) {
            if (cm.id < maxSoFar) inversions++;
            else maxSoFar = cm.id;
        }

        long lost = committed.stream().map(Committed::id).filter(id -> !seenByCursor.contains(id)).count();
        return new Result(committed.size(), inversions, lost);
    }

    // ------------------------------------------------------------------ SQL

    private int newRoom(int members) throws SQLException {
        try (Connection c = connect(SCRATCH_DB)) {
            int roomId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chat_rooms (created_at, creator_id, is_group, room_name) VALUES (now(), 'u0', true, 'm3') RETURNING room_id")) {
                try (ResultSet rs = ps.executeQuery()) { rs.next(); roomId = rs.getInt(1); }
            }
            // chat_members.user_id는 users FK다. 발신자만큼 사용자를 먼저 만든다(멱등).
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (user_id, created_at, language_code, name, password, remaining_leave_days, role, ship_yard_area, tts_enabled) "
                            + "VALUES (?, now(), 'ko', ?, 'x', 0, 'USER', 'm3', false) ON CONFLICT (user_id) DO NOTHING")) {
                for (int w = 0; w < members; w++) {
                    ps.setString(1, "u" + w);
                    ps.setString(2, "u" + w);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chat_members (joined_at, room_id, user_id) VALUES (now(), ?, ?)")) {
                for (int w = 0; w < members; w++) {
                    ps.setInt(1, roomId);
                    ps.setString(2, "u" + w);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return roomId;
        }
    }

    private long currentMaxId(int roomId) throws SQLException {
        try (Connection c = connect(SCRATCH_DB);
             PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(message_id), 0) FROM chat_messages WHERE room_id = ?")) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    /** {@code ChatMessages} INSERT. {@code seq}는 ROOM_SEQ 변형에서만 있다. */
    private static long insert(Connection c, int roomId, String sender, Long seq) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_messages (content, message_type, sender_id, sent_at, room_id, room_seq) VALUES (?, 'TEXT', ?, now(), ?, ?) RETURNING message_id")) {
            ps.setString(1, "m3");
            ps.setString(2, sender);
            ps.setInt(3, roomId);
            if (seq == null) ps.setNull(4, java.sql.Types.BIGINT); else ps.setLong(4, seq);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    /**
     * ADR-0008 D8 후보. {@code touchRoom}이 잡는 것과 같은 행 락 안에서 번호를 받는다 —
     * 새 락이 아니라 {@code saveMessage}가 이미 내는 비용이다. {@code last_message_*} 갱신도 여기 합친다.
     */
    private static long nextRoomSeq(Connection c, int roomId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE chat_rooms SET last_message_seq = last_message_seq + 1, last_message_content = ?, last_message_at = NOW() "
                        + "WHERE room_id = ? RETURNING last_message_seq")) {
            ps.setString(1, "m3");
            ps.setInt(2, roomId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    /** {@code ChatJdbcRepository.updateLastReadTime} 그대로. */
    private static void touchMember(Connection c, int roomId, String sender) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE chat_members SET last_read_time = NOW() WHERE room_id = ? AND user_id = ?")) {
            ps.setInt(1, roomId);
            ps.setString(2, sender);
            ps.executeUpdate();
        }
    }

    /** {@code ChatJdbcRepository.updateLastMessage} 그대로 — 모든 발신자가 같은 행을 갱신한다. */
    private static void touchRoom(Connection c, int roomId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE chat_rooms SET last_message_content = ?, last_message_at = NOW() WHERE room_id = ?")) {
            ps.setString(1, "m3");
            ps.setInt(2, roomId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 접속

    /** 스크래치 DB로 붙는다. {@code ContainerTestSupport.connect()}는 기본 DB로만 가서 따로 둔다. */
    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrlFor(db), username(), password());
    }
}
