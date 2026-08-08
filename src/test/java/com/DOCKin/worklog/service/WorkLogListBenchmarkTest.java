package com.DOCKin.worklog.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A5 실측 (2/2): <b>목록 API의 쿼리가 규모에 따라 얼마나 느려지는가.</b>
 *
 * <h3>1/2와 나눈 이유</h3>
 * {@code WorkLogListQueryCountTest}가 재는 <b>쿼리 개수</b>는 규모와 무관해서 60건짜리 표본으로
 * 정확히 세지고, 그래서 CI에서 돈다. 여기서 재는 <b>지연시간</b>은 반대다 — 100만 건을 적재해야
 * 의미가 있고 적재에만 분 단위가 걸린다. 둘을 한 테스트에 두면 <b>CI에서 돌 수 있는 절반까지</b>
 * 같이 못 돌게 된다.
 *
 * <h3>왜 합성 텍스트로 재는가</h3>
 * 지연시간은 <b>행 수 / 행 크기 / 인덱스 유무</b>에 좌우되고 문장의 의미와는 무관하다.
 * {@code CorpusSeedGeneratorTest}가 실제 문형으로 코퍼스를 만드는 것은 <b>recall</b>을 재기
 * 위해서였고(벡터 분포가 의미에 좌우된다), 여기서는 그 이유가 없다.
 * 대신 <b>행 크기는 실제와 맞춘다</b> — 로컬 코퍼스의 {@code log_text} 평균이 259자다.
 *
 * <h3>왜 진짜 {@code work_logs}에 넣는가</h3>
 * {@code BruteForceSearchBenchmarkTest}는 {@code bench_document_chunks}라는 별도 테이블을 만들어 썼다.
 * 여기서는 그럴 수 없다. <b>측정 대상이 "이 테이블에 인덱스가 PK 하나뿐이다"라는 사실 자체</b>이므로,
 * 복제본을 만드는 순간 그 복제본의 인덱스 구성은 내가 정한 것이 되어 아무것도 증명하지 못한다.
 *
 * <p>대신 흔적을 남기지 않는 데 공을 들였다 — 표식 접두사로 시작 시점과 종료 시점에 모두 지우고,
 * 이 테스트가 만든 인덱스는 이름으로 구분해 지운다. <b>중간에 죽어도</b> 다음 실행이 먼저 청소한다.
 *
 * <h3>이 테스트가 남기면 안 되는 것 하나</h3>
 * 벤치 행이 남은 채 앱이 뜨면 {@code rag.indexing.cron}(매일 03시)이 그것들을 <b>임베딩하려 든다.</b>
 * 100만 건이면 TEI 호출이 그만큼 나가고 {@code document_chunks}가 오염된다.
 * 아래 {@code cleanUp}이 실패해도 다음 실행의 첫 단계가 같은 일을 하므로 두 겹이다.
 *
 * <h3>실행</h3>
 * <pre>
 * DB_PASSWORD=... RUN_LIST_BENCHMARK=1 ./gradlew test --tests "*WorkLogListBenchmarkTest"
 * </pre>
 * 스위치를 {@code DB_PASSWORD}와 별도로 둔 이유는 P0-11이 지적한 것과 같다 —
 * {@code DB_PASSWORD}는 이제 '오래 걸림'만 뜻하는데, 이 테스트는 그중에서도 <b>수 분</b>이고
 * 진짜 테이블에 100만 행을 넣는다. 같은 스위치로 묶으면 의도치 않게 돌아간다.
 */
class WorkLogListBenchmarkTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/dockindb";

    /** 벤치 행의 제목 접두사 / 사용자 접두사. 지울 대상을 특정하는 유일한 수단이다. */
    private static final String MARKER = "[벤치] ";
    private static final String BENCH_USER_PREFIX = "bch";

    /**
     * 측정 규모. 로드맵 2-3이 "연 100만 건이 쌓이는 테이블"이라고 적은 그 값이 상한이다.
     * 10만을 함께 재는 것은 <b>기울기</b>를 보기 위해서다 — 한 점만 있으면 "느리다"까지고,
     * 두 점이 있어야 "행 수에 비례하는가 / 로그인가"를 말할 수 있다.
     */
    private static final int[] SCALES = {100_000, 1_000_000};

    /** 구역 수. 실제 시드와 같은 6개다. 사용자를 나눠 담아 선택도를 만든다. */
    private static final int AREAS = 6;

    /** 벤치 사용자 수. 코퍼스 생성기의 500명과 맞춘다 → 구역당 약 83명이 IN 절에 들어간다. */
    private static final int BENCH_USERS = 500;

    /** 이미지가 붙는 비율(1/N). 1/2에서 확인한 "행당 1쿼리"의 대상 테이블 크기를 결정한다. */
    private static final int IMAGE_EVERY = 3;

    /**
     * 희귀 단어를 심는 비율(1/N). ⑤ 키워드 검색이 실제로 스캔을 하게 만드는 값이다.
     * 10,000이면 100만 행에서 100건이 걸리고, {@code LIMIT 20}이 조기 종료하지 못한다.
     */
    private static final int RARE_EVERY = 10_000;

    private static final int PAGE_SIZE = 20;
    private static final int WARMUP_RUNS = 2;
    private static final int MEASURED_RUNS = 5;

    /**
     * 뒷정리에만 쓰는 인덱스. 측정 대상이 아니라 <b>지우는 동안만</b> 필요하고 끝나면 없앤다.
     * {@link #cleanUp}의 주석이 왜 필요한지 설명한다.
     */
    private static final Map<String, String> CLEANUP_FK_INDEXES = new LinkedHashMap<>();

    static {
        CLEANUP_FK_INDEXES.put("cleanup_idx_work_log_images_log",
                "CREATE INDEX cleanup_idx_work_log_images_log ON work_log_images (work_log_id)");
        CLEANUP_FK_INDEXES.put("cleanup_idx_log_images_log",
                "CREATE INDEX cleanup_idx_log_images_log ON log_images (log_id)");
        CLEANUP_FK_INDEXES.put("cleanup_idx_work_log_comments_log",
                "CREATE INDEX cleanup_idx_work_log_comments_log ON work_log_comments (log_id)");
    }

    /** 이 테스트가 만드는 인덱스. 이름 접두사로 "내가 만든 것"을 구분한다. */
    private static final Map<String, String> BENCH_INDEXES = new LinkedHashMap<>();

    static {
        // 목록의 필터 컬럼. 지금은 없어서 user_id IN (...)이 매번 전체를 훑는다.
        BENCH_INDEXES.put("bench_idx_work_logs_user",
                "CREATE INDEX bench_idx_work_logs_user ON work_logs (user_id)");
        // 필터 + 정렬을 한 인덱스로. 목록 API의 기본 정렬이 created_at DESC이므로
        // user_id만 걸면 정렬이 여전히 남는다.
        BENCH_INDEXES.put("bench_idx_work_logs_user_created",
                "CREATE INDEX bench_idx_work_logs_user_created ON work_logs (user_id, created_at DESC)");
        // 1/2에서 확인한 행당 1쿼리가 실제로 닿는 곳. 이 테이블에도 PK뿐이라
        // 이미지 한 건을 찾을 때마다 전체를 훑는다.
        BENCH_INDEXES.put("bench_idx_work_log_images_log",
                "CREATE INDEX bench_idx_work_log_images_log ON work_log_images (work_log_id)");
    }

    @Test
    @DisplayName("목록 API 쿼리 지연시간 - 10만 / 100만 건, 인덱스 전후")
    void 목록_API_실측() throws Exception {
        String password = System.getenv("DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "DB_PASSWORD 환경변수가 없어 벤치마크를 건너뜁니다.");

        String run = System.getenv("RUN_LIST_BENCHMARK");
        Assumptions.assumeTrue(run != null && !run.isBlank(),
                "RUN_LIST_BENCHMARK 환경변수가 없어 건너뜁니다. "
                        + "진짜 work_logs에 최대 100만 행을 넣었다 지우는 측정이므로 별도 스위치를 둔다.");

        try (Connection conn = DriverManager.getConnection(URL, "root", password)) {
            try {
                cleanUp(conn);
                List<String> owners = createBenchUsers(conn);

                for (int scale : SCALES) {
                    load(conn, scale, owners);

                    // 같은 구역의 사용자들. 목록 API가 IN 절에 통째로 넣는 그 목록이다.
                    List<String> areaOwners = ownersInArea(owners, 0);

                    System.out.println();
                    System.out.printf("=== A5 (2/2) 목록 API 지연시간 - work_logs %,d행 / 같은 구역 %d명 ===%n",
                            totalRows(conn), areaOwners.size());
                    System.out.printf("%-34s | %-12s | %-12s | %-9s%n",
                            "쿼리", "인덱스 없음", "인덱스 있음", "배수");
                    System.out.println("-".repeat(78));

                    List<Case> cases = cases(areaOwners);

                    dropBenchIndexes(conn);
                    analyze(conn);
                    Map<String, Double> before = measureAll(conn, cases);

                    createBenchIndexes(conn);
                    analyze(conn);
                    Map<String, Double> after = measureAll(conn, cases);

                    for (Case c : cases) {
                        double b = before.get(c.name());
                        double a = after.get(c.name());
                        System.out.printf("%-34s | %-12.1f | %-12.1f | %-9s%n",
                                c.name(), b, a, a == 0 ? "-" : "%.1f배".formatted(b / a));
                    }
                    System.out.println();

                    printPlans(conn, cases);
                    dropBenchIndexes(conn);
                }
            } finally {
                cleanUp(conn);
            }
        } catch (SQLException e) {
            Assumptions.abort("PostgreSQL(localhost:5432)에 접속할 수 없어 건너뜁니다: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 측정 대상
    // ------------------------------------------------------------------

    /**
     * 측정할 쿼리 하나.
     *
     * @param repeats 한 번의 측정에서 몇 번 실행할지. 목록 API가 <b>행마다</b> 던지는 쿼리는
     *                한 번만 재면 실제 요청 비용이 아니다 — 1/2가 센 20번을 그대로 반영한다.
     */
    private record Case(String name, String sql, List<Object> params, int repeats) {
        Case(String name, String sql, List<Object> params) {
            this(name, sql, params, 1);
        }
    }

    /**
     * Hibernate가 실제로 만드는 문장을 그대로 옮긴다.
     *
     * <p>JPQL을 흉내 내 다시 쓰면 <b>측정 대상이 실제 실행되는 것과 달라진다.</b>
     * 예를 들어 {@code findByMemberIn}은 {@code Page}이므로 본문과 COUNT 두 문장이 나가고,
     * 둘의 비용이 서로 다르다 — 합쳐서 재면 어느 쪽이 문제인지 알 수 없다.
     */
    private List<Case> cases(List<String> areaOwners) {
        String in = "?" + ",?".repeat(areaOwners.size() - 1);
        List<Object> owners = new ArrayList<>(areaOwners);

        List<Case> cases = new ArrayList<>();

        // 1. 전체 목록 첫 페이지 - findByMemberIn의 본문. 정렬이 없다.
        //
        //    [2026-08-08 정정] 이 줄의 전제가 P2-15-3으로 뒤집혔다. 원래는 "컨트롤러의
        //    @PageableDefault에 sort가 비어 있어 GET /api/work-logs가 정해지지 않은 순서로
        //    응답한다"는 이유로 정렬 없이 뒀던 것인데, 그 결함을 고쳤다.
        //    이제 실제 경로에 대응하는 것은 ①이 아니라 ④다.
        //
        //    그래도 ①을 지우지 않는다. 지금은 성격이 바뀌어 '정렬을 뺐을 때의 기준선'이고,
        //    ④와의 차이가 곧 정렬의 비용이다 -- 인덱스 전후로 그 차이가 어떻게 변하는지가
        //    (created_at 인덱스를 만들 것인가) 이 벤치의 판단 대상 중 하나다.
        //    이미 잰 10만/100만 수치도 이 정의 위에서 나왔으므로 문장을 바꾸면 그 숫자를 버려야 한다.
        cases.add(new Case("① 구역 목록 1페이지",
                "SELECT * FROM work_logs WHERE user_id IN (" + in + ") LIMIT " + PAGE_SIZE + " OFFSET 0",
                owners));

        // 2. 같은 목록의 COUNT. Page<>가 매 요청 함께 던진다.
        //    PostgreSQL은 MVCC라 정확한 count에 가시성 확인이 필요해 LIMIT의 이득이 없다.
        cases.add(new Case("② 구역 목록 COUNT",
                "SELECT count(*) FROM work_logs WHERE user_id IN (" + in + ")", owners));

        // 3. 뒤 페이지. OFFSET은 앞의 행을 읽고 버리므로 페이지 번호에 비례해 비싸진다.
        cases.add(new Case("③ 구역 목록 500페이지",
                "SELECT * FROM work_logs WHERE user_id IN (" + in + ") LIMIT " + PAGE_SIZE
                        + " OFFSET " + (500 * PAGE_SIZE), owners));

        // 4. 정렬을 붙인 첫 페이지. P2-15-3 이후 이것이 실제 경로다.
        //    다만 컨트롤러 기본값은 이제 (created_at, log_id) DESC이고 여기는 created_at 하나다.
        //    두 번째 키는 PK라 이미 정렬된 순서를 따라가므로 비용 측면에서는 첫 키가 지배한다.
        //    측정을 이미 끝낸 문장이라 그대로 둔다 -- 다음 실행 때 log_id를 붙여 확인할 항목이다.
        cases.add(new Case("④ 구역 목록 1페이지 정렬",
                "SELECT * FROM work_logs WHERE user_id IN (" + in + ")"
                        + " ORDER BY created_at DESC LIMIT " + PAGE_SIZE + " OFFSET 0", owners));

        // 5. 키워드 검색. 양쪽 와일드카드라 B-tree로는 어떤 인덱스도 쓸 수 없다 --
        //    위 인덱스를 만들어도 이 줄만 안 변하는 것이 pg_trgm이 필요한 근거가 된다.
        cases.add(new Case("⑤ 키워드 검색 (LIKE %kw%)",
                "SELECT * FROM work_logs WHERE title LIKE ? OR log_text LIKE ? LIMIT " + PAGE_SIZE,
                List.of("%크랭크축%", "%크랭크축%")));

        // 6. 1/2가 센 행당 1쿼리. 한 페이지가 20번 던지므로 20번을 한 단위로 잰다.
        cases.add(new Case("⑥ 이미지 조회 ×" + PAGE_SIZE + " (N+1)",
                "SELECT * FROM work_log_images WHERE work_log_id = ?",
                List.of(1L), PAGE_SIZE));

        return cases;
    }

    // ------------------------------------------------------------------
    // 실행
    // ------------------------------------------------------------------

    private Map<String, Double> measureAll(Connection conn, List<Case> cases) throws SQLException {
        Map<String, Double> results = new LinkedHashMap<>();
        for (Case c : cases) {
            for (int i = 0; i < WARMUP_RUNS; i++) {
                execute(conn, c);
            }
            double total = 0;
            for (int i = 0; i < MEASURED_RUNS; i++) {
                long start = System.nanoTime();
                execute(conn, c);
                total += (System.nanoTime() - start) / 1_000_000.0;
            }
            results.put(c.name(), total / MEASURED_RUNS);
        }
        return results;
    }

    /**
     * 한 케이스를 {@code repeats}번 실행한다.
     *
     * <p><b>결과를 실제로 읽는다.</b> PostgreSQL은 커서로 결과를 흘려보내므로,
     * {@code executeQuery()}만 부르고 {@code next()}를 돌리지 않으면
     * <b>전송과 역직렬화 비용이 측정에서 빠진다.</b> ADR-0002의 브루트포스 측정에서
     * 병목이 DB가 아니라 클라이언트 전송(81%)이었던 것이 바로 그 비용이다.
     */
    private void execute(Connection conn, Case c) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(c.sql())) {
            for (int r = 0; r < c.repeats(); r++) {
                for (int i = 0; i < c.params().size(); i++) {
                    Object param = c.params().get(i);
                    // 이미지 N+1은 매번 다른 log_id를 조회한다. 같은 값을 20번 부르면
                    // 캐시가 데워져 실제 페이지 조회보다 싸게 나온다.
                    if (param instanceof Long id) {
                        ps.setLong(i + 1, id + r);
                    } else {
                        ps.setObject(i + 1, param);
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rs.getObject(1);
                    }
                }
            }
        }
    }

    /** 인덱스 없는 상태의 실행 계획을 남긴다. 숫자만 있으면 "왜 느린가"는 다음 사람이 다시 봐야 한다. */
    private void printPlans(Connection conn, List<Case> cases) throws SQLException {
        System.out.println("--- 실행 계획 (인덱스 없는 상태) ---");
        dropBenchIndexes(conn);
        analyze(conn);
        for (Case c : cases) {
            System.out.println("  " + c.name());
            try (PreparedStatement ps = conn.prepareStatement("EXPLAIN (ANALYZE, BUFFERS) " + c.sql())) {
                for (int i = 0; i < c.params().size(); i++) {
                    ps.setObject(i + 1, c.params().get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        System.out.println("    " + rs.getString(1));
                    }
                }
            }
        }
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 적재 / 정리
    // ------------------------------------------------------------------

    /**
     * 벤치 사용자를 만든다. 구역에 고르게 나눠 담아 {@code findByShipYardArea}의 결과 크기를 만든다.
     *
     * <p>비밀번호는 어떤 평문과도 맞지 않는 BCrypt 형식이다 — 코퍼스 생성기와 같은 이유로,
     * 남더라도 이 계정으로 로그인이 <b>우연히 성공하는 일이 없어야</b> 한다.
     */
    private List<String> createBenchUsers(Connection conn) throws SQLException {
        List<String> owners = new ArrayList<>(BENCH_USERS);
        String sql = """
                INSERT INTO users (user_id, name, password, role, language_code,
                                   ship_yard_area, remaining_leave_days, tts_enabled, work_shift, created_at)
                VALUES (?, ?, ?, 'USER', 'ko', ?, 15, false, 'MORNING', TIMESTAMP '2024-07-01 09:00:00')
                """;
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < BENCH_USERS; i++) {
                String userId = "%s%04d".formatted(BENCH_USER_PREFIX, i);
                owners.add(userId);
                ps.setString(1, userId);
                ps.setString(2, "벤치%04d".formatted(i)); // name은 varchar(10)이다
                ps.setString(3, "$2a$10$" + "0".repeat(53));
                ps.setString(4, "벤치구역%d".formatted(i % AREAS));
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } finally {
            conn.setAutoCommit(true);
        }
        return owners;
    }

    private List<String> ownersInArea(List<String> owners, int area) {
        List<String> result = new ArrayList<>();
        for (int i = area; i < owners.size(); i += AREAS) {
            result.add(owners.get(i));
        }
        return result;
    }

    /**
     * 목표 행 수까지 채운다.
     *
     * <p><b>JDBC 배치가 아니라 {@code generate_series}로 넣는다.</b> 코퍼스 생성기가 JDBC를 쓰는 것은
     * 생성한 {@code log_id}를 받아 번역본과 짝지어야 하기 때문인데, 여기서는 그럴 일이 없다.
     * 서버 안에서 만들면 100만 행이 왕복 없이 들어간다.
     *
     * <p>본문 길이를 로컬 코퍼스 평균(259자)에 맞춘다. 행 크기가 다르면 순차 스캔이 읽는
     * 페이지 수가 달라져 <b>지연시간이 통째로 어긋난다.</b>
     */
    private void load(Connection conn, int target, List<String> owners) throws SQLException {
        long current = totalRows(conn);
        long toInsert = target - current;
        if (toInsert <= 0) {
            return;
        }

        System.out.printf("  적재 중... 현재 %,d행 → 목표 %,d행%n", current, target);
        long start = System.currentTimeMillis();

        // 이번에 넣는 행만 골라내기 위한 경계. 두 번째 규모에서 이미 이미지가 붙은 행을
        // 다시 훑지 않으려는 것인데, NOT EXISTS로 거르면 인덱스 없는 work_log_images에
        // 안티조인이 걸린다 -- 측정 전에 적재가 더 오래 걸리는 상황이 된다.
        long previousMaxId = maxLogId(conn);

        try (Statement st = conn.createStatement()) {
            // i % 500으로 소유자를 고르고, i를 분 단위로 흩뿌려 created_at 분포를 만든다.
            // 본문은 한 문장을 반복해 259자 근처로 맞춘다 -- 행 크기가 다르면 순차 스캔이 읽는
            // 페이지 수가 달라진다.
            //
            // 희귀 단어를 1/RARE_EVERY 행에만 심는 것이 ⑤의 성립 조건이다. 모든 행이 같은 단어를
            // 갖고 있으면 LIMIT 20이 스무 번째 행에서 멈춰 버려 -- 스캔 비용이 측정에서 통째로
            // 빠지고 "LIKE %kw%는 빠르다"는 거짓 결론이 나온다. 실제 검색어는 대개 드물다.
            st.executeUpdate("""
                    INSERT INTO work_logs (title, log_text, created_at, updated_at, user_id, equipment_id)
                    SELECT '%s' || i,
                           repeat('베어링 마모로 이상 진동이 발생하여 규정 토크로 재체결했다. ', 8)
                             || CASE WHEN i %% %d = 0 THEN ' 특이사항으로 크랭크축 균열을 발견했다.' ELSE '' END,
                           TIMESTAMP '2024-08-01 06:00:00' + (i * INTERVAL '1 minute'),
                           TIMESTAMP '2024-08-01 06:00:00' + (i * INTERVAL '1 minute'),
                           '%s' || lpad((i %% %d)::text, 4, '0'),
                           NULL
                      FROM generate_series(%d, %d) AS i
                    """.formatted(MARKER, RARE_EVERY, BENCH_USER_PREFIX, BENCH_USERS, current + 1, target));

            // 이미지. ⑥이 훑는 테이블의 크기가 여기서 정해진다.
            st.executeUpdate("""
                    INSERT INTO work_log_images (image_url, work_log_id)
                    SELECT 'https://example.invalid/bench.jpg', log_id
                      FROM work_logs
                     WHERE log_id > %d AND title LIKE '%s%%' AND log_id %% %d = 0
                    """.formatted(previousMaxId, MARKER, IMAGE_EVERY));
        }

        System.out.printf("  적재 완료 (%,dms)%n", System.currentTimeMillis() - start);
    }

    private long totalRows(Connection conn) throws SQLException {
        return scalar(conn, "SELECT count(*) FROM work_logs");
    }

    /** 이번 적재분의 경계. 테이블이 비어 있으면 0이므로 모든 행이 '새 행'이 된다. */
    private long maxLogId(Connection conn) throws SQLException {
        return scalar(conn, "SELECT coalesce(max(log_id), 0) FROM work_logs");
    }

    private long scalar(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * 통계를 갱신한다.
     *
     * <p><b>빠뜨리면 인덱스를 만들어도 안 쓴다.</b> 플래너는 통계를 보고 고르는데,
     * 방금 만든 인덱스와 방금 넣은 100만 행에 대한 통계가 없으면 순차 스캔이 더 싸다고 판단한다.
     * 그러면 "인덱스가 효과 없다"는 <b>틀린 결론</b>이 나온다.
     */
    private void analyze(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("ANALYZE work_logs");
            st.execute("ANALYZE work_log_images");
        }
    }

    private void createBenchIndexes(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String ddl : BENCH_INDEXES.values()) {
                st.execute(ddl);
            }
        }
    }

    private void dropBenchIndexes(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String name : BENCH_INDEXES.keySet()) {
                st.execute("DROP INDEX IF EXISTS " + name);
            }
        }
    }

    /**
     * 벤치 흔적을 지운다. <b>시작과 끝 양쪽에서 부른다</b> — 중간에 죽어도 다음 실행이 청소한다.
     *
     * <p>순서가 중요하다. {@code work_log_images.work_log_id}와 {@code work_logs.user_id}가
     * 각각 FK이고 CASCADE가 없다(코퍼스 생성기와 같은 이유다).
     *
     * <h4>인덱스를 먼저 만드는 이유 — 첫 실행에서 삭제가 측정보다 오래 걸렸다</h4>
     * 부모({@code work_logs}) 행을 지울 때 PostgreSQL은 자식 테이블마다
     * "이 행을 참조하는 것이 있는가"를 확인한다. <b>FK 컬럼에 인덱스가 없으면 자식 테이블을
     * 통째로 훑는다 — 삭제하는 행마다, 자식 테이블마다.</b>
     * {@code log_images} / {@code work_log_comments} / {@code work_log_images} 셋 다 인덱스가 없어
     * (P2-15-4) 98만 행 삭제가 <b>24분을 넘겼다.</b>
     *
     * <p>{@code work_log_images}는 살아 있는 행이 0건인데도 비싸다. 바로 앞 문장이 지운 33만 건이
     * <b>죽은 튜플로 남아 페이지를 차지</b>하고, 순차 스캔은 그것을 전부 읽기 때문이다.
     * {@code VACUUM}으로 잘라내려 해도 <b>진행 중인 삭제가 잠금을 쥐고 있어 거부된다.</b>
     *
     * <p>그래서 지우기 <b>전에</b> 인덱스를 만든다. 이 인덱스는 운영 스키마에 남기지 않는다 —
     * 필요 여부는 P2-15-4에서 따로 결정할 일이고, 정리 도구가 스키마를 바꾸면 안 된다.
     */
    private void cleanUp(Connection conn) throws SQLException {
        dropBenchIndexes(conn);
        try (Statement st = conn.createStatement()) {
            for (Map.Entry<String, String> fk : CLEANUP_FK_INDEXES.entrySet()) {
                st.execute("DROP INDEX IF EXISTS " + fk.getKey());
                st.execute(fk.getValue());
            }

            st.executeUpdate("""
                    DELETE FROM work_log_images
                     WHERE work_log_id IN (SELECT log_id FROM work_logs WHERE title LIKE '%s%%')
                    """.formatted(MARKER));
            st.executeUpdate("DELETE FROM work_logs WHERE title LIKE '%s%%'".formatted(MARKER));
            st.executeUpdate("DELETE FROM users WHERE user_id LIKE '%s%%'".formatted(BENCH_USER_PREFIX));

            for (String name : CLEANUP_FK_INDEXES.keySet()) {
                st.execute("DROP INDEX IF EXISTS " + name);
            }
            // 지운 자리를 회수한다. 안 하면 다음 실행이 죽은 튜플 위에서 측정한다 --
            // 순차 스캔이 읽는 페이지 수가 달라져 지연시간이 통째로 어긋난다.
            st.execute("VACUUM work_logs");
            st.execute("VACUUM work_log_images");
        }
    }
}
