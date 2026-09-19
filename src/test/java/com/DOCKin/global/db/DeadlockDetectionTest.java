package com.DOCKin.global.db;

import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검증: {@code deadlock_timeout=1s}가 실제로 교착의 한쪽을 죽이는가? (DB-IMPROVEMENT-PLAN C3)
 *
 * <h3>왜 이 테스트가 있나 — 설정은 있는데 교착을 재현한 적이 없었다</h3>
 * {@code compose.yaml}과 {@link ContainerTestSupport}가 {@code -c deadlock_timeout=1s}를 준다.
 * 그런데 이 저장소의 락 테스트 둘({@code LockTimeoutVerificationTest}, {@code LeaveBalanceConcurrencyTest})은
 * 전부 <b>한 방향</b> 대기다 — 교착이 아니라 줄서기. 교착 감지기가 도는 것을 본 적이 없으니
 * 1초가 먹는지, 아니면 {@code lock_timeout=5s}가 먼저 와서 교착도 락 대기 타임아웃으로 보이는지 몰랐다.
 *
 * <h3>어떻게 재현하나</h3>
 * 행 둘(A·B)을 두 커넥션이 <b>반대 순서</b>로 {@code FOR UPDATE}한다.
 * T1: A 잠금 → (둘 다 첫 잠금을 쥔 것을 배리어로 확인) → B 요청(막힘).
 * T2: B 잠금 → (배리어) → T1이 막힌 것이 관측된 뒤 → A 요청 → 이 순간 사이클이 완성된다.
 *
 * <h3>무엇으로 판정하나 — 두 타임아웃이 서로 다른 오류를 낸다</h3>
 * <ul>
 *   <li>교착 감지가 먹으면: 대기 시작 <b>약 1초</b> 뒤 한쪽이 SQLSTATE {@code 40P01}
 *       ({@code deadlock detected})로 죽고 다른 쪽은 그 행을 얻어 커밋한다.</li>
 *   <li>안 먹으면: 둘 다 <b>5초</b>를 채운 뒤 {@code 55P03}({@code lock_not_available}, lock_timeout)으로
 *       <b>둘 다</b> 죽는다. 이 경우도 테스트는 끝나기는 하므로, 시간과 SQLSTATE를 같이 봐야 설정이
 *       먹는지 갈린다.</li>
 * </ul>
 * 그래서 희생자의 대기 시간이 1초 이상 {@code lock_timeout} 미만인지, 코드가 {@code 40P01}인지,
 * 살아남은 쪽이 정확히 하나인지를 셋 다 본다.
 *
 * <h3>덤 — 기다리는 동안 {@code pg_stat_activity}에 무엇이 보이나 (실험 카드 ③)</h3>
 * T1이 B를 기다리는 동안 세 번째 커넥션이 {@code pg_stat_activity}·{@code pg_blocking_pids()}를 읽는다.
 * 장애 #4(13분 44초 hang)에서 사람이 눈으로 본 {@code wait_event='transactionid'}가 그것이다.
 * 이 관측이 T2의 두 번째 잠금 <b>앞에</b> 오도록 순서를 고정했으므로 시간 경합이 없다.
 *
 * <p>실행: {@code ./gradlew test --tests "*DeadlockDetectionTest"}
 */
class DeadlockDetectionTest extends ContainerTestSupport {
    private static final String TABLE = "bench_deadlock";

    /** ContainerTestSupport가 컨테이너에 준 -c deadlock_timeout=1s / -c lock_timeout=5s. */
    private static final long DEADLOCK_TIMEOUT_MS = 1_000;
    private static final long LOCK_TIMEOUT_MS = 5_000;

    /** PostgreSQL SQLSTATE. */
    private static final String DEADLOCK_DETECTED = "40P01";

    @Test
    @DisplayName("반대 순서 FOR UPDATE — 약 1초 뒤 한쪽만 40P01로 죽고 다른 쪽은 커밋한다")
    void 교착은_1초_안에_한쪽만_죽는다() throws Exception {
        try (Connection setup = connect()) {
            prepare(setup);
        }

        CyclicBarrier bothHoldFirst = new CyclicBarrier(2);
        CountDownLatch t1AtSecond = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        // T1: 둘째 잠금 요청 직전에 신호를 보낸다. 관측 스레드가 그 대기를 읽을 시간을 준다.
        Future<Outcome> t1 = pool.submit(() -> run("T1", "A", "B", bothHoldFirst, t1AtSecond::countDown));
        // T2: T1이 막힌 모습이 관측된 뒤에만 사이클을 닫는다.
        Future<Outcome> t2 = pool.submit(() -> run("T2", "B", "A", bothHoldFirst,
                () -> observed.await(10, TimeUnit.SECONDS)));

        assertTrue(t1AtSecond.await(10, TimeUnit.SECONDS), "T1이 둘째 잠금에 도달하지 않았다");
        Waiting waiting = observeWaiting("T1", 2_000);
        observed.countDown();

        Outcome o1 = t1.get(30, TimeUnit.SECONDS);
        Outcome o2 = t2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.println();
        System.out.println("=== 교착 (deadlock_timeout=1s, lock_timeout=5s) ===");
        System.out.println("T1 대기 중 pg_stat_activity: " + waiting);
        System.out.println("T1: " + o1);
        System.out.println("T2: " + o2);

        // 관측: 기다리는 쪽은 상대의 트랜잭션 ID 락(transactionid)을 기다리고, 막은 pid는 상대다.
        assertEquals("Lock", waiting.waitEventType(), "T1은 Lock 종류의 대기여야 한다");
        assertEquals("transactionid", waiting.waitEvent(),
                "행 잠금 대기는 상대 트랜잭션 ID 락으로 보인다 (장애 #4에서 본 그것)");
        assertTrue(waiting.blockingPids().contains(o2.pid()),
                "pg_blocking_pids()가 상대(T2)의 pid를 가리켜야 한다: " + waiting.blockingPids() + " vs " + o2.pid());

        // 판정: 정확히 하나가 40P01로 죽고, 나머지는 커밋.
        Outcome victim = o1.sqlState() != null ? o1 : o2;
        Outcome survivor = victim == o1 ? o2 : o1;
        assertEquals(DEADLOCK_DETECTED, victim.sqlState(),
                "희생자는 deadlock detected(40P01)여야 한다. 55P03이면 교착 감지가 아니라 lock_timeout이 먼저 온 것");
        assertTrue(survivor.committed(), "살아남은 쪽은 커밋해야 한다: " + survivor);

        // 시간: 감지기는 deadlock_timeout 뒤에 처음 돈다. lock_timeout(5s)보다 확실히 앞이어야 한다.
        assertTrue(victim.secondLockWaitMs() >= DEADLOCK_TIMEOUT_MS - 100,
                "감지가 deadlock_timeout보다 먼저 왔다 — 설정값이 다른 것: " + victim.secondLockWaitMs() + "ms");
        assertTrue(victim.secondLockWaitMs() < LOCK_TIMEOUT_MS - 1_000,
                "lock_timeout에 가깝다 — 교착 감지가 아니라 락 대기 타임아웃으로 풀린 것: " + victim.secondLockWaitMs() + "ms");
        System.out.printf(">>> 희생자 %s가 %dms 만에 40P01, %s는 커밋. 55P03은 0건.%n",
                victim.name(), victim.secondLockWaitMs(), survivor.name());
    }

    /** 한 트랜잭션: first를 잠그고, 배리어, (훅), second를 잠그고 커밋. */
    private Outcome run(String name, String first, String second, CyclicBarrier bothHoldFirst,
                        Hook beforeSecond) throws Exception {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            // 관측 스레드가 pg_stat_activity에서 이 백엔드를 이름으로 찾는다.
            try (Statement st = conn.createStatement()) {
                st.execute("SET application_name = '" + name + "'");
            }
            int pid = backendPid(conn);
            lockRow(conn, first);
            bothHoldFirst.await(10, TimeUnit.SECONDS);
            beforeSecond.run();

            long t0 = System.nanoTime();
            try {
                lockRow(conn, second);
                long waited = (System.nanoTime() - t0) / 1_000_000;
                conn.commit();
                return new Outcome(name, pid, true, null, waited);
            } catch (SQLException e) {
                long waited = (System.nanoTime() - t0) / 1_000_000;
                conn.rollback();
                return new Outcome(name, pid, false, e.getSQLState(), waited);
            }
        }
    }

    /**
     * 이름이 {@code appName}인 백엔드가 Lock 대기로 보일 때까지 폴링한다.
     * 관측은 T2가 사이클을 닫기 전이므로 교착 감지기가 끼어들지 않는다.
     */
    private Waiting observeWaiting(String appName, long maxWaitMs) throws Exception {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        try (Connection obs = connect();
             PreparedStatement ps = obs.prepareStatement(
                     "SELECT wait_event_type, wait_event, pg_blocking_pids(pid) "
                     + "FROM pg_stat_activity WHERE application_name = ?")) {
            ps.setString(1, appName);
            while (System.currentTimeMillis() < deadline) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && "Lock".equals(rs.getString(1))) {
                        Integer[] pids = (Integer[]) rs.getArray(3).getArray();
                        return new Waiting(rs.getString(1), rs.getString(2), List.of(pids));
                    }
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError(appName + "이 " + maxWaitMs + "ms 안에 Lock 대기로 보이지 않았다");
    }

    private void lockRow(Connection conn, String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT k FROM " + TABLE + " WHERE k = ? FOR UPDATE")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "행 " + key + "가 없다");
            }
        }
    }

    private int backendPid(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void prepare(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + TABLE);
            st.execute("CREATE TABLE " + TABLE + " (k VARCHAR(8) PRIMARY KEY, v INT NOT NULL)");
            st.execute("INSERT INTO " + TABLE + " VALUES ('A', 0), ('B', 0)");
        }
    }

    @FunctionalInterface
    private interface Hook {
        void run() throws Exception;
    }

    private record Outcome(String name, int pid, boolean committed, String sqlState, long secondLockWaitMs) {}

    private record Waiting(String waitEventType, String waitEvent, List<Integer> blockingPids) {}
}
