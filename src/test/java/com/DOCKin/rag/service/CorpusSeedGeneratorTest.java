package com.DOCKin.rag.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 측정용 대량 코퍼스 생성기. {@code SEED_CORPUS_SIZE}가 있을 때만 실행된다.
 *
 * <h3>왜 필요한가</h3>
 * 손으로 쓴 시드는 작업일지 16건이다. 화면을 돌려보기에는 충분하지만
 * <b>ANN recall 측정에는 쓸 수 없다.</b> 후보가 16개면 HNSW가 틀릴 일 자체가 없고,
 * 인덱스는 애초에 만들어지지도 않는다(pgvector는 후보가 적으면 순차 스캔을 고른다).
 * recall이 의미를 가지려면 최소 수천 건, 실측 기준선(10만 청크)에 맞추려면 수만 건이 필요하다.
 *
 * <h3>왜 벡터가 아니라 텍스트를 만드는가</h3>
 * {@link BruteForceSearchBenchmarkTest}는 난수 벡터를 직접 넣는다. <b>지연시간</b>은
 * 후보 개수와 차원에만 좌우되므로 그것으로 충분했다. 그러나 <b>recall은 벡터의 분포에
 * 좌우된다.</b> 균등 난수 벡터는 서로 거의 직교해 최근접이라는 개념이 흐려지고,
 * HNSW에게는 현실보다 훨씬 불리한 최악 조건이다. 그 위에서 잰 recall은 실제보다 나쁘게 나온다.
 *
 * <p>그래서 이 생성기는 {@code work_logs}에 <b>한국어 텍스트</b>만 넣고,
 * 임베딩은 평소 경로 그대로 {@link IndexingService}가 TEI를 호출해 만들게 한다.
 * 실제 모델이 만든 벡터라야 실제 분포를 갖는다.
 *
 * <h3>2026-08-06에 두 가지를 더했다 — 둘 다 측정이 막혀 있었기 때문이다</h3>
 *
 * <p><b>1. 소유자를 3명에서 {@value #OWNER_COUNT}명으로.</b> 이전에는
 * {@code R__seed_sample.sql}이 만든 {@code worker01~03}에 분배했고, 그래서 각자가 코퍼스의
 * 약 1/3을 가졌다. 권한 선필터의 선택도가 33.6%라는 뜻인데, <b>HNSW가 필터에서 무너지는 것은
 * 선택도가 높을수록 심해지므로 이것은 애초에 약한 조건이다</b>(ADR-0006 8-2).
 * 실제 서비스는 사용자가 수백 명이고 각자 1% 미만을 소유한다. 여기서는 0.2%로 만든다.
 *
 * <p><b>2. 베트남어 번역본 생성.</b> 이전에는 {@code work_log_translations}에 손도 대지 않아
 * 번역 코퍼스가 7건뿐이었다. 색인 경로({@link IndexingService#indexTranslations})는 완성되어
 * 있는데 먹일 데이터가 없어서, <b>교차언어가 이 서비스의 메인 축인데 그 측정만 계속 막혀 있었다.</b>
 *
 * <p>핵심은 <b>같은 슬롯 튜플에서 두 언어를 렌더링한다</b>는 것이다({@link Composition}).
 * 한국어 본문과 베트남어 번역본이 정말로 같은 내용을 가리키므로,
 * {@code (log_id, language_code)}가 <b>지어내지 않은 정답 라벨</b>이 된다.
 *
 * <h3>한계 셋 — 이것은 "실데이터"가 아니다</h3>
 *
 * <p><b>1. 어휘가 좁다.</b> 문장을 슬롯 조합으로 만들기 때문에 실제 작업일지보다 서로 더 비슷하다.
 * 임베딩 공간에서 실제보다 조밀하게 뭉칠 가능성이 높고, 그러면 최근접 이웃 사이의 거리 차가
 * 작아져 <b>recall이 실제보다 낮게(=보수적으로) 나오는 방향</b>으로 치우친다.
 * 따라서 여기서 얻은 recall 수치는 <b>하한선으로만 읽어야 한다.</b>
 *
 * <p><b>2. 번역문의 품질을 사람이 검수하지 않았다.</b> 아래 {@code *_VI} 배열은 기계가 지은
 * 것이며 베트남어 원어민이 확인한 적이 없다. 실제 번역기가 만드는 문장과 문체가 다를 수 있고,
 * 그러면 <b>번역본 색인의 이득(A2)이 실제와 다르게 측정될 수 있다.</b> 이 코퍼스로 얻는 결론은
 * "번역본을 넣으면 검색이 이만큼 달라진다"의 <b>방향</b>이지 정확한 크기가 아니다.
 *
 * <p><b>3. 번역 비율이 가정이다.</b> {@value #TRANSLATION_PERCENT}%로 두었으나 실제 서비스에서
 * 몇 %의 일지가 번역되는지는 모른다 `[실측 필요]`.
 *
 * <h3>실행</h3>
 * <pre>
 * DB_PASSWORD=... SEED_CORPUS_SIZE=20000 ./gradlew test --tests "*CorpusSeedGeneratorTest*"
 * </pre>
 */
class CorpusSeedGeneratorTest {

    private static final String URL = "jdbc:postgresql://localhost:5432/dockindb";

    /**
     * 생성된 행을 식별하는 제목 접두사.
     *
     * <p>{@code work_logs}에는 "이 행이 생성물인가"를 담을 컬럼이 없다. 컬럼을 새로 만들면
     * 마이그레이션이 따라오고, 그것은 <b>로컬 도구 때문에 운영 스키마를 바꾸는</b> 일이다.
     * 제목 접두사는 스키마를 건드리지 않으면서 재실행 시 지울 대상을 특정할 수 있는 최소 수단이다.
     */
    private static final String MARKER = "[생성] ";

    /**
     * 생성 사용자의 {@code user_id} 접두사. 위 {@link #MARKER}와 같은 역할이다.
     *
     * <p>{@code R__seed_sample.sql}이 만든 {@code worker01~03}과 겹치지 않아야 한다 --
     * 겹치면 재실행 시 손으로 쓴 시드 사용자를 지운다.
     */
    private static final String GENERATED_USER_PREFIX = "gen";

    /**
     * 생성할 소유자 수. 선택도 = 1 / {@value} 이므로 0.2%다.
     *
     * <p>이 값이 측정의 핵심 조건이다. ADR-0006 8-2가 미해결로 남긴 것이
     * <b>"선택도가 낮을 때도 HNSW가 top-k를 채우는가"</b> 이고, 그것을 보려면
     * 선필터가 통과시키는 후보가 충분히 적어야 한다.
     */
    private static final int OWNER_COUNT = 500;

    /** 베트남어 사용자 비율. 나머지는 한국어다. 다국어 현장이라는 전제를 코퍼스에 반영한다. */
    private static final int VIETNAMESE_USER_PERCENT = 30;

    /** 번역본이 존재하는 작업일지의 비율 `[실측 필요]`. */
    private static final int TRANSLATION_PERCENT = 30;

    /** 번역 언어. e5는 다국어 모델이므로 언어 코드는 검색에 쓰이지 않고 정답 라벨로만 쓰인다. */
    private static final String TRANSLATION_LANGUAGE = "vi";

    @Test
    @DisplayName("대량 작업일지 코퍼스 생성 - SEED_CORPUS_SIZE가 있을 때만 실행")
    void 코퍼스_생성() throws Exception {
        String password = System.getenv("DB_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank(),
                "DB_PASSWORD 환경변수가 없어 코퍼스 생성을 건너뜁니다.");

        String sizeEnv = System.getenv("SEED_CORPUS_SIZE");
        Assumptions.assumeTrue(sizeEnv != null && !sizeEnv.isBlank(),
                "SEED_CORPUS_SIZE 환경변수가 없어 코퍼스 생성을 건너뜁니다. "
                        + "예: SEED_CORPUS_SIZE=20000");

        int size = Integer.parseInt(sizeEnv.trim());
        if (size <= 0) {
            throw new IllegalArgumentException("SEED_CORPUS_SIZE는 1 이상이어야 합니다: " + size);
        }

        try (Connection conn = DriverManager.getConnection(URL, "root", password)) {
            assertEquipmentExists(conn);

            long start = System.currentTimeMillis();
            Removed removed = removePreviouslyGenerated(conn);
            List<String> owners = generateUsers(conn);
            Generated generated = generate(conn, size, owners);
            int translations = generateTranslations(conn, generated.pendingTranslations());
            int orphans = removeOrphanChunks(conn);

            System.out.println();
            System.out.println("=== 코퍼스 생성 완료 ===");
            System.out.printf("이전 생성분 삭제 : 번역 %,d / 일지 %,d / 사용자 %,d%n",
                    removed.translations(), removed.workLogs(), removed.users());
            System.out.printf("생성 사용자      : %,d명 (소유자당 %.2f%%)%n",
                    owners.size(), 100.0 / owners.size());
            System.out.printf("생성 작업일지    : %,d건%n", generated.workLogs());
            System.out.printf("생성 번역본      : %,d건 (%s, 목표 %d%%)%n",
                    translations, TRANSLATION_LANGUAGE, TRANSLATION_PERCENT);
            System.out.printf("고아 청크 정리   : %,d건%n", orphans);
            System.out.printf("소요             : %,dms%n", System.currentTimeMillis() - start);
            System.out.println();
            System.out.println("다음 단계 - 임베딩을 만들려면 색인을 돌려야 합니다:");
            System.out.println("  docker compose -f compose.yaml -f compose.gc.yaml up -d dockin-app");
            System.out.println();
        } catch (SQLException e) {
            Assumptions.abort("PostgreSQL(localhost:5432)에 접속할 수 없어 건너뜁니다: " + e.getMessage());
        }
    }

    /**
     * 한국어 슬롯과 베트남어 슬롯의 개수가 같은지 본다. <b>DB가 필요 없어 CI에서도 돈다.</b>
     *
     * <p>대응이 깨지는 방식이 두 가지인데 위험도가 다르다.
     * <ul>
     *   <li>베트남어 쪽이 <b>짧으면</b> {@code ArrayIndexOutOfBoundsException}으로 즉시 터진다.
     *       시끄럽지만 안전하다</li>
     *   <li>베트남어 쪽이 <b>길면</b> 아무 일도 일어나지 않는다. 뒤쪽 항목이 영영 안 뽑힐 뿐이다.
     *       <b>이쪽이 위험하다</b> -- 코퍼스는 만들어지고 측정도 돌아가는데 어휘 분포만 조용히 달라진다</li>
     * </ul>
     *
     * <p>둘 다 잡으려면 개수를 직접 비교하는 수밖에 없다. 위 코퍼스 생성 테스트는
     * {@code SEED_CORPUS_SIZE}가 없으면 건너뛰므로, 이 검사가 없으면
     * <b>어긋난 채로 커밋되어도 아무도 모른다.</b>
     */
    @Test
    @DisplayName("슬롯 배열의 한국어/베트남어 개수가 일치한다 - 어긋나면 번역본이 원본과 다른 것을 가리킨다")
    void 슬롯_대응_검사() {
        assertSameLength("EQUIPMENT", EQUIPMENT, EQUIPMENT_VI);
        assertSameLength("PART", PART, PART_VI);
        assertSameLength("SYMPTOM", SYMPTOM, SYMPTOM_VI);
        assertSameLength("CAUSE", CAUSE, CAUSE_VI);
        assertSameLength("ACTION", ACTION, ACTION_VI);
        assertSameLength("FOLLOW_UP", FOLLOW_UP, FOLLOW_UP_VI);
    }

    private void assertSameLength(String name, String[] ko, String[] vi) {
        if (ko.length != vi.length) {
            throw new AssertionError(
                    "%s의 한국어(%d)와 베트남어(%d) 개수가 다릅니다. 같은 인덱스가 같은 의미여야 합니다."
                            .formatted(name, ko.length, vi.length));
        }
    }

    /**
     * 두 언어가 같은 조합에서 나오는지 본다. 같은 {@link Composition}이면
     * <b>양쪽 모두 같은 설비/부위를 가리켜야 한다.</b>
     *
     * <p>{@code Composition}을 도입한 이유가 이것이고, 렌더러를 고치다 한쪽만 바꾸면
     * 번역본이 무관한 문서가 된다. 그때 교차언어 recall은 <b>0에 가깝게 나오는데
     * 원인이 모델인지 코퍼스인지 알 수 없다.</b>
     */
    @Test
    @DisplayName("같은 조합에서 두 언어가 같은 대상을 가리킨다")
    void 두_언어가_같은_대상을_가리킨다() {
        Random random = new Random(1L);
        for (int i = 0; i < 200; i++) {
            Composition c = Composition.of(random);

            if (!c.titleKo().contains(EQUIPMENT[c.equipment()])
                    || !c.titleVi().contains(EQUIPMENT_VI[c.equipment()])) {
                throw new AssertionError("제목이 같은 설비를 가리키지 않습니다: " + c.titleKo() + " / " + c.titleVi());
            }
            if (!c.bodyKo().contains(SYMPTOM[c.symptom()])
                    || !c.bodyVi().contains(SYMPTOM_VI[c.symptom()])) {
                throw new AssertionError("본문이 같은 현상을 가리키지 않습니다: " + c.titleKo());
            }
            // 긴 본문은 청킹 경로를 태우기 위한 것이므로 양쪽 다 길어야 한다.
            // 한쪽만 길면 청크 수가 달라져 A3(원문·번역본 중복 점유) 측정이 어긋난다.
            if (c.longForm() && (c.bodyKo().length() < 500 || c.bodyVi().length() < 500)) {
                throw new AssertionError("긴 본문인데 한쪽이 짧습니다: ko %d / vi %d"
                        .formatted(c.bodyKo().length(), c.bodyVi().length()));
            }
        }
    }

    // ------------------------------------------------------------------
    // 정리
    // ------------------------------------------------------------------

    private record Removed(int translations, int workLogs, int users) {}

    /**
     * 이전 생성분을 지운다. 지우지 않고 또 넣으면 실행할 때마다 코퍼스가 불어나
     * <b>"몇 건에서 잰 수치인가"를 알 수 없게 된다.</b>
     *
     * <p><b>순서가 중요하다.</b> {@code work_log_translations.log_id}와 {@code work_logs.user_id}가
     * 각각 FK이고 CASCADE가 없다. 역순으로 지우면 FK 위반으로 실패한다.
     * <pre>
     *   번역본  ->  작업일지  ->  사용자
     * </pre>
     */
    private Removed removePreviouslyGenerated(Connection conn) throws SQLException {
        int translations;
        try (PreparedStatement ps = conn.prepareStatement("""
                DELETE FROM work_log_translations
                 WHERE log_id IN (SELECT log_id FROM work_logs WHERE title LIKE ?)
                """)) {
            ps.setString(1, MARKER + "%");
            translations = ps.executeUpdate();
        }

        int workLogs;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM work_logs WHERE title LIKE ?")) {
            ps.setString(1, MARKER + "%");
            workLogs = ps.executeUpdate();
        }

        int users;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM users WHERE user_id LIKE ?")) {
            ps.setString(1, GENERATED_USER_PREFIX + "%");
            users = ps.executeUpdate();
        }

        return new Removed(translations, workLogs, users);
    }

    /**
     * 원본이 사라진 청크를 지운다.
     *
     * <p><b>이 정리를 빠뜨리면 조용히 틀린다.</b> {@code document_chunks}는 여러 원본 테이블을
     * {@code (source_type, source_id)}로 다형 참조하므로 <b>FK가 없고, 따라서 CASCADE도 없다.</b>
     * 위에서 원본을 지워도 청크는 그대로 남는다.
     *
     * <p>남은 청크는 여전히 검색에 걸리고 챗봇 근거로 주입된다 -- 원본이 없는 근거이므로
     * 화면에서 추적할 수도 없다. 게다가 recall을 측정할 때 정답 집합에 없는 후보가 섞여
     * 수치를 오염시킨다.
     *
     * <p>번역본 청크도 같은 이유로 함께 정리한다. 이쪽은 <b>원본 작업일지가 아니라
     * 번역본 자신의 PK를 {@code source_id}로 쓰므로</b> 참조 대상 테이블이 다르다.
     */
    private int removeOrphanChunks(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            return st.executeUpdate("""
                    DELETE FROM document_chunks
                     WHERE (source_type = 'WORK_LOG'
                            AND source_id NOT IN (SELECT log_id FROM work_logs))
                        OR (source_type = 'WORK_LOG_TRANSLATION'
                            AND source_id NOT IN (SELECT translation_id FROM work_log_translations))
                    """);
        }
    }

    /**
     * 장비가 없으면 멈춘다.
     *
     * <p>사용자는 이제 이 생성기가 직접 만들지만 장비는 {@code R__seed_sample.sql}에 의존한다.
     * 없는 장비로 넣으면 FK 위반으로 실패하는데, 그때 나오는 것은 제약 이름이 박힌 SQL 오류라
     * <b>"seed 프로파일로 앱을 한 번 띄워 시드를 넣어야 한다"는 진짜 원인이 드러나지 않는다.</b>
     */
    private void assertEquipmentExists(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM equipment WHERE equipment_id BETWEEN 1 AND 5")) {
            rs.next();
            if (rs.getInt(1) < 5) {
                throw new IllegalStateException(
                        "장비 시드(1~5번)가 없습니다. 먼저 seed 프로파일로 앱을 띄워 R__seed_sample.sql을 적용하세요.");
            }
        }
    }

    // ------------------------------------------------------------------
    // 생성
    // ------------------------------------------------------------------

    /**
     * 소유자를 만든다.
     *
     * <p>비밀번호는 로그인에 쓰이지 않는 더미다 -- 이 사용자들은 측정용 소유자일 뿐
     * 로그인 경로를 타지 않는다. 그래도 평문을 넣지는 않는다. 나중에 누군가 이 계정으로
     * 로그인을 시도했을 때 <b>우연히 성공하는 일이 없어야</b> 하기 때문이다.
     * BCrypt 형식이지만 어떤 평문과도 맞지 않는 값을 넣는다.
     */
    private List<String> generateUsers(Connection conn) throws SQLException {
        Random random = new Random(20260806L);
        List<String> owners = new ArrayList<>(OWNER_COUNT);

        String sql = """
                INSERT INTO users (user_id, name, password, role, language_code,
                                   ship_yard_area, remaining_leave_days, tts_enabled, work_shift, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= OWNER_COUNT; i++) {
                String userId = "%s%04d".formatted(GENERATED_USER_PREFIX, i);
                owners.add(userId);

                ps.setString(1, userId);
                // name은 varchar(10)이다. 긴 이름을 넣으면 잘리는 게 아니라 INSERT가 실패한다.
                ps.setString(2, "작업자%04d".formatted(i));
                // BCrypt 형식이지만 어떤 평문과도 맞지 않는 값이다. 시드 계정과 길이(60)도 맞춘다.
                ps.setString(3, "$2a$10$" + "0".repeat(53));
                ps.setString(4, "USER");
                ps.setString(5, random.nextInt(100) < VIETNAMESE_USER_PERCENT ? "vi" : "ko");
                ps.setString(6, AREA[random.nextInt(AREA.length)]);
                ps.setInt(7, 15);
                ps.setBoolean(8, false);
                ps.setString(9, WORK_SHIFT[random.nextInt(WORK_SHIFT.length)]);
                ps.setTimestamp(10, Timestamp.valueOf(LocalDateTime.of(2024, 7, 1, 9, 0)));
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
        return owners;
    }

    private record PendingTranslation(long logId, String ownerUserId, Composition composition,
                                      LocalDateTime writtenAt) {}

    private record Generated(int workLogs, List<PendingTranslation> pendingTranslations) {}

    /**
     * 작업일지를 생성한다.
     *
     * <p>{@code log_id}를 명시하지 않고 IDENTITY에 맡긴다 -- 시퀀스가 정상적으로 올라가므로
     * {@code R__seed_sample.sql}이 필요로 했던 {@code setval} 보정이 여기서는 필요 없다.
     *
     * <p><b>생성 키를 받아 두는 이유</b> -- 번역본을 넣으려면 {@code log_id}가 필요하다.
     * 나중에 제목으로 다시 조회해 짝을 맞추는 방법도 있지만, 그러면 <b>어떤 일지가 어떤
     * 슬롯 조합에서 나왔는지</b>를 다시 알아낼 수 없다. 번역본은 같은 조합에서 렌더링해야
     * 의미가 대응하므로 여기서 조합과 키를 함께 들고 나간다.
     */
    private Generated generate(Connection conn, int size, List<String> owners) throws SQLException {
        // 시드를 고정해 재현 가능하게 한다. 같은 SEED_CORPUS_SIZE로 두 번 돌리면 같은 코퍼스가 나온다.
        Random random = new Random(20260805L);

        // 작성일을 2년에 걸쳐 흩뿌린다.
        //
        // 간격을 고정하면(예: 36분마다) 기간이 건수에 따라 달라진다 -- 2,000건이면 50일,
        // 20,000건이면 500일이다. ADR-0007의 만료 기간 N은 "채택된 근거의 원본 작성일 분포"를
        // 보고 정하는 값이라, 코퍼스 기간이 규모에 따라 흔들리면 측정 자체가 성립하지 않는다.
        // 그래서 기간을 먼저 고정하고 간격을 건수로 나눈다.
        LocalDateTime base = LocalDateTime.of(2024, 8, 1, 6, 0);
        long spanMinutes = 2L * 365 * 24 * 60;
        long stepMinutes = Math.max(1, spanMinutes / size);

        String sql = """
                INSERT INTO work_logs (title, log_text, created_at, updated_at, user_id, equipment_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        List<PendingTranslation> pending = new ArrayList<>();
        // 한 배치 안의 조합을 들고 있다가 생성 키와 짝짓는다.
        List<Composition> batchCompositions = new ArrayList<>();
        List<String> batchOwners = new ArrayList<>();
        List<LocalDateTime> batchDates = new ArrayList<>();

        conn.setAutoCommit(false);
        int inserted = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql, new String[]{"log_id"})) {
            for (int i = 0; i < size; i++) {
                // 하루 약 40건 페이스로 날짜를 흩뿌린다. ADR-0007의 만료 기간 N을 재려면
                // created_at이 한 시점에 뭉쳐 있으면 안 된다 -- 분포가 곧 측정 대상이다.
                LocalDateTime writtenAt = base.plusMinutes(i * stepMinutes);

                // 제목과 본문이 같은 설비를 가리켜야 한다.
                //
                // 각자 뽑게 뒀더니 "무인 도장설비 2호기 …"라는 제목에 "플라즈마 절단기 4호기에서 …"
                // 라는 본문이 붙었다. IndexingService.toTarget()이 title + "\n" + logText를
                // 한 덩어리로 임베딩하므로, 한 청크 안에 서로 다른 설비가 섞이면 그 벡터는
                // 어느 쪽도 제대로 가리키지 않는다 -- recall 측정을 오염시킨다.
                Composition composition = Composition.of(random);
                String owner = owners.get(random.nextInt(owners.size()));

                ps.setString(1, MARKER + composition.titleKo());
                ps.setString(2, composition.bodyKo());
                ps.setTimestamp(3, Timestamp.valueOf(writtenAt));
                ps.setTimestamp(4, Timestamp.valueOf(writtenAt));
                ps.setString(5, owner);
                // 장비는 R__seed_sample.sql이 만든 1~5번. 일부는 장비 없는 일지다.
                int equipment = random.nextInt(6);
                if (equipment == 0) {
                    ps.setNull(6, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(6, equipment);
                }
                ps.addBatch();

                batchCompositions.add(composition);
                batchOwners.add(owner);
                batchDates.add(writtenAt);

                if ((i + 1) % 1000 == 0) {
                    inserted += flushWorkLogs(conn, ps, batchCompositions, batchOwners, batchDates,
                            random, pending);
                    System.out.printf("  생성 중... %,d / %,d%n", inserted, size);
                }
            }
            inserted += flushWorkLogs(conn, ps, batchCompositions, batchOwners, batchDates,
                    random, pending);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
        return new Generated(inserted, pending);
    }

    /** 배치를 밀어 넣고 생성 키를 조합과 짝지어 번역 대상을 고른다. */
    private int flushWorkLogs(Connection conn, PreparedStatement ps,
                              List<Composition> compositions, List<String> owners,
                              List<LocalDateTime> dates, Random random,
                              List<PendingTranslation> pending) throws SQLException {
        if (compositions.isEmpty()) {
            return 0;
        }
        ps.executeBatch();

        int index = 0;
        try (ResultSet keys = ps.getGeneratedKeys()) {
            while (keys.next() && index < compositions.size()) {
                if (random.nextInt(100) < TRANSLATION_PERCENT) {
                    pending.add(new PendingTranslation(
                            keys.getLong(1), owners.get(index), compositions.get(index), dates.get(index)));
                }
                index++;
            }
        }
        conn.commit();

        int count = compositions.size();
        compositions.clear();
        owners.clear();
        dates.clear();
        return count;
    }

    /**
     * 번역본을 만든다.
     *
     * <p><b>원본과 같은 {@link Composition}에서 렌더링한다.</b> 이것이 이 생성기의 핵심이고,
     * 그렇지 않으면 "번역본"이라는 이름만 붙은 무관한 문서가 된다. 같은 조합에서 나왔으므로
     * {@code (log_id, language_code)}가 <b>지어내지 않은 정답 라벨</b>이 되고,
     * ADR-0006 11절이 남겨둔 "교차언어 recall@k"를 그 위에서 잴 수 있다.
     *
     * <p>{@code original_title}/{@code original_text}도 함께 채운다. 실제 번역 경로가 그렇게
     * 저장하며, 비워 두면 <b>번역본만 보고 원문을 확인하는 화면 동선이 검증되지 않는다.</b>
     */
    private int generateTranslations(Connection conn, List<PendingTranslation> pending) throws SQLException {
        if (pending.isEmpty()) {
            return 0;
        }

        String sql = """
                INSERT INTO work_log_translations
                    (log_id, user_id, language_code, original_title, original_text,
                     translated_title, translated_text, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        conn.setAutoCommit(false);
        int inserted = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PendingTranslation t : pending) {
                Composition c = t.composition();
                ps.setLong(1, t.logId());
                ps.setString(2, t.ownerUserId());
                ps.setString(3, TRANSLATION_LANGUAGE);
                ps.setString(4, MARKER + c.titleKo());
                ps.setString(5, c.bodyKo());
                ps.setString(6, c.titleVi());
                ps.setString(7, c.bodyVi());
                ps.setTimestamp(8, Timestamp.valueOf(t.writtenAt()));
                ps.setTimestamp(9, Timestamp.valueOf(t.writtenAt()));
                ps.addBatch();

                if (++inserted % 1000 == 0) {
                    ps.executeBatch();
                    conn.commit();
                    System.out.printf("  번역 생성 중... %,d / %,d%n", inserted, pending.size());
                }
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
        return inserted;
    }

    // ------------------------------------------------------------------
    // 문장 조합
    //
    // 슬롯을 곱해 어휘 다양성을 만든다. 조합 수는 대략
    //   장비 12 x 부위 10 x 현상 12 x 원인 10 x 조치 12 x 후속 10 ≈ 2,000만 가지이므로
    // 수만 건 규모에서 완전히 같은 본문이 나오는 일은 사실상 없다.
    //
    // 다만 조합이 많다는 것과 의미가 다양하다는 것은 다르다 -- 문형이 여섯 개뿐이라
    // 임베딩 공간에서는 실제 작업일지보다 좁게 뭉친다. 클래스 주석의 "한계"가 이 이야기다.
    //
    // *_VI 배열은 같은 인덱스가 같은 의미다. 이 대응이 깨지면 번역본이 원본과 다른 것을
    // 가리키게 되고, 교차언어 정답 라벨이 통째로 거짓이 된다. 항목을 더할 때 반드시 양쪽에 더한다.
    // ------------------------------------------------------------------

    private static final String[] EQUIPMENT = {
            "CO2 용접기", "겐트리 크레인", "무인 도장설비", "플라즈마 절단기", "고소작업대",
            "유압 프레스", "블라스팅 장비", "이송 컨베이어", "공기압축기", "집진 설비",
            "지게차", "발전기"
    };
    private static final String[] EQUIPMENT_VI = {
            "máy hàn CO2", "cần trục giàn", "thiết bị sơn tự động", "máy cắt plasma",
            "xe nâng người làm việc trên cao", "máy ép thủy lực", "thiết bị phun cát",
            "băng tải vận chuyển", "máy nén khí", "thiết bị hút bụi", "xe nâng", "máy phát điện"
    };

    private static final String[] PART = {
            "송급 롤러", "유압 실린더", "제어 패널", "베어링부", "감속기",
            "배관 플랜지", "전동기 축", "냉각 팬", "안전 스위치", "케이블 그랜드"
    };
    private static final String[] PART_VI = {
            "con lăn cấp liệu", "xi lanh thủy lực", "bảng điều khiển", "bộ phận vòng bi",
            "hộp giảm tốc", "mặt bích đường ống", "trục động cơ điện", "quạt làm mát",
            "công tắc an toàn", "ốc siết cáp"
    };

    private static final String[] SYMPTOM = {
            "이상 진동이 발생했다", "간헐적으로 정지했다", "온도가 규정치를 넘었다",
            "누유가 확인되었다", "소음이 평소보다 커졌다", "출력이 불안정했다",
            "경보가 반복 발생했다", "동작이 지연되었다", "압력이 유지되지 않았다",
            "표시값이 튀는 현상이 있었다", "기동에 실패했다", "과전류로 차단되었다"
    };
    private static final String[] SYMPTOM_VI = {
            "đã xảy ra rung động bất thường", "đã dừng gián đoạn",
            "nhiệt độ đã vượt mức quy định", "đã phát hiện rò rỉ dầu",
            "tiếng ồn đã lớn hơn bình thường", "công suất đầu ra không ổn định",
            "cảnh báo đã phát ra lặp lại", "thao tác đã bị chậm trễ",
            "áp suất đã không được duy trì", "đã có hiện tượng giá trị hiển thị nhảy",
            "đã không khởi động được", "đã bị ngắt do quá dòng"
    };

    private static final String[] CAUSE = {
            "체결 볼트 이완", "윤활 부족", "필터 막힘", "센서 접점 불량", "절연 저하",
            "개스킷 경화", "베어링 마모", "냉각수 부족", "제어 파라미터 오설정", "이물질 유입"
    };
    private static final String[] CAUSE_VI = {
            "bu lông siết bị lỏng", "thiếu bôi trơn", "tắc bộ lọc", "tiếp điểm cảm biến kém",
            "suy giảm cách điện", "gioăng bị chai cứng", "vòng bi bị mòn", "thiếu nước làm mát",
            "cài đặt sai tham số điều khiển", "dị vật lọt vào"
    };

    private static final String[] ACTION = {
            "규정 토크로 재체결했다", "그리스를 보충했다", "필터를 교체했다",
            "접점을 청소하고 재결선했다", "절연 저항을 측정하고 케이블을 교체했다",
            "개스킷을 신품으로 교체했다", "베어링을 교체하고 정렬을 재조정했다",
            "냉각수를 보충하고 누설 여부를 확인했다", "파라미터를 기준값으로 되돌렸다",
            "내부를 분해 청소했다", "임시 조치 후 정비팀에 이관했다", "예비품으로 교체했다"
    };
    private static final String[] ACTION_VI = {
            "đã siết lại theo lực siết quy định", "đã bổ sung mỡ bôi trơn", "đã thay bộ lọc",
            "đã vệ sinh tiếp điểm và đấu lại dây", "đã đo điện trở cách điện và thay cáp",
            "đã thay gioăng mới", "đã thay vòng bi và căn chỉnh lại",
            "đã bổ sung nước làm mát và kiểm tra rò rỉ", "đã đưa tham số về giá trị chuẩn",
            "đã tháo và vệ sinh bên trong", "đã xử lý tạm thời và chuyển cho tổ bảo trì",
            "đã thay bằng phụ tùng dự phòng"
    };

    private static final String[] FOLLOW_UP = {
            "다음 정기 점검에서 재확인이 필요하다.",
            "동일 증상이 재발하면 부품 수명 도래로 보고 교체를 건의한다.",
            "점검 주기를 단축하자고 반장에게 보고했다.",
            "예비품 재고가 없어 발주를 요청했다.",
            "인접 호기에서도 같은 증상이 있는지 확인하기로 했다.",
            "작업 표준서에 이 절차가 빠져 있어 추가를 요청했다.",
            "교대조에 경과 관찰을 인계했다.",
            "원인이 완전히 규명되지 않아 기록만 남긴다.",
            "재발 방지를 위해 체결 이력을 기록하기로 했다.",
            "설비 이력 카드에 반영했다."
    };
    private static final String[] FOLLOW_UP_VI = {
            "Cần kiểm tra lại trong đợt bảo dưỡng định kỳ tới.",
            "Nếu hiện tượng tái diễn, coi như phụ tùng hết tuổi thọ và đề nghị thay thế.",
            "Đã báo cáo tổ trưởng đề nghị rút ngắn chu kỳ kiểm tra.",
            "Không còn phụ tùng dự phòng nên đã yêu cầu đặt hàng.",
            "Đã quyết định kiểm tra xem máy bên cạnh có hiện tượng tương tự không.",
            "Quy trình này còn thiếu trong tiêu chuẩn công việc nên đã yêu cầu bổ sung.",
            "Đã bàn giao việc theo dõi cho ca sau.",
            "Nguyên nhân chưa được xác định rõ nên chỉ ghi nhận lại.",
            "Đã quyết định ghi lại lịch sử siết để phòng tái diễn.",
            "Đã cập nhật vào thẻ lý lịch thiết bị."
    };

    /**
     * 조선소 구역과 근무조. 값의 형태를 {@code R__seed_sample.sql}에 맞춘다 --
     * 생성 사용자만 다른 표기를 쓰면 화면에서 <b>생성물인지 아닌지가 눈에 띄어</b>
     * 실제 데이터를 흉내낸다는 목적이 흐려진다.
     */
    private static final String[] AREA = {
            "1도크 선각공장", "2도크 의장공장", "3도크 도장공장",
            "안벽 배관공장", "조립공장", "블록공장"
    };

    /** {@code work_shift}는 시드가 MORNING/AFTERNOON/NIGHT를 쓴다. 다른 값을 넣지 않는다. */
    private static final String[] WORK_SHIFT = {"MORNING", "AFTERNOON", "NIGHT"};

    /**
     * 한 작업일지를 이루는 모든 선택. <b>언어와 무관한 "무엇을 말할 것인가"만 담는다.</b>
     *
     * <p>렌더링을 분리한 이유가 여기 있다. 예전에는 {@code body()} 안에서 슬롯을 뽑았기 때문에
     * 같은 내용을 다른 언어로 다시 만들 방법이 없었다. 조합을 먼저 확정해 두면
     * {@link #bodyKo()}와 {@link #bodyVi()}가 <b>같은 사실을 두 언어로 말하는 것</b>이 된다.
     */
    private record Composition(
            int equipment, int unit, int part,
            int symptom, int cause, int action, int followUp,
            int trialMinutes, int measuredInt, int measuredFrac,
            boolean longForm,
            // longForm에서만 쓰이는 값들. 짧은 본문에서는 무시된다.
            int part2, int symptom2, int cause2, int action2, int followUp2,
            int months, int similarCases, int leadWeeks, int workers, int hours) {

        static Composition of(Random r) {
            return new Composition(
                    r.nextInt(EQUIPMENT.length), 1 + r.nextInt(9), r.nextInt(PART.length),
                    r.nextInt(SYMPTOM.length), r.nextInt(CAUSE.length),
                    r.nextInt(ACTION.length), r.nextInt(FOLLOW_UP.length),
                    10 + r.nextInt(50), 10 + r.nextInt(80), r.nextInt(10),
                    // 다섯 건 중 하나는 500자(TARGET_CHARS)를 넘겨 2청크 이상이 되게 한다.
                    //
                    // 처음에는 문장 몇 개만 덧붙였는데 최대가 334자에 그쳐 전부 1청크였다. 그러면
                    // RetrievalService의 "원본 문서당 최대 2청크" 제한이 한 번도 실행되지 않고,
                    // 청크 인덱스가 1 이상인 행도 생기지 않아 재색인 경로의 일부가 검증되지 않는다.
                    r.nextInt(5) == 0,
                    r.nextInt(PART.length), r.nextInt(SYMPTOM.length), r.nextInt(CAUSE.length),
                    r.nextInt(ACTION.length), r.nextInt(FOLLOW_UP.length),
                    1 + r.nextInt(11), 2 + r.nextInt(5), 1 + r.nextInt(6),
                    2 + r.nextInt(3), 1 + r.nextInt(6));
        }

        // --- 한국어 ---

        String subjectKo() {
            return "%s %d호기 %s".formatted(EQUIPMENT[equipment], unit, PART[part]);
        }

        String titleKo() {
            return "%s %s".formatted(subjectKo(), unit % 2 == 0 ? "이상 조치" : "점검 결과");
        }

        String bodyKo() {
            StringBuilder sb = new StringBuilder();
            sb.append("%s에서 %s. ".formatted(subjectKo(), SYMPTOM[symptom]));
            appendCauseKo(sb, "점검 결과 ", cause);
            // ACTION은 마침표로 끝나지 않는다. 여기서 붙이지 않으면 다음 문장과 이어 붙어
            // 종결 부호가 사라진다 -- FixedSizeChunkingStrategy는 종결 부호로 문장 경계를 찾으므로
            // 그 자리는 경계를 못 찾고 강제 절단된다(SENTENCE_LOOKBACK 실측 6-6의 STT 사례와 같다).
            sb.append("%s. ".formatted(ACTION[action]));
            sb.append("조치 후 %d분간 시운전하여 정상 동작을 확인했다. ".formatted(trialMinutes));
            // 숫자 뒤 조사는 읽는 방식에 따라 로/으로가 갈린다("67.2로" / "16으로"). 규칙을 구현하느니
            // 조사를 안 쓰는 문형으로 피한다 -- 이 문장은 전 건에 들어가므로 어색한 표기가
            // 있으면 코퍼스 전체에 같은 흔적이 박힌다.
            sb.append("측정값은 %d.%d이며 기준 범위 이내였다. ".formatted(measuredInt, measuredFrac));
            sb.append(FOLLOW_UP[followUp]);

            if (longForm) {
                sb.append(" 추가로 인접 계통도 함께 점검했다. ");
                sb.append("%s %d호기 %s에서도 %s. ".formatted(
                        EQUIPMENT[equipment], unit, PART[part2], SYMPTOM[symptom2]));
                appendCauseKo(sb, "이쪽은 ", cause2);
                sb.append("%s. ".formatted(ACTION[action2]));
                sb.append("두 건 모두 같은 원인 계열로 보이므로 개별 조치가 아니라 계통 단위 점검이 필요하다. ");
                sb.append("이력을 확인해보니 지난 %d개월 사이 같은 계통에서 %d건의 유사 사례가 있었다. "
                        .formatted(months, similarCases));
                sb.append("당시에도 임시 조치로 마무리되어 근본 원인이 남아 있었던 것으로 보인다. ");
                sb.append("설비 담당과 협의해 정기 점검 항목에 이 계통을 포함시키기로 했다. ");
                sb.append("교체 부품의 조달 기간이 %d주로 길어 예비품을 미리 확보해 두어야 한다. "
                        .formatted(leadWeeks));
                sb.append("작업 중 안전 조치로는 전원을 차단하고 잠금 표지를 부착한 뒤 진행했다. ");
                sb.append("잔압이 남아 있을 수 있어 배출을 먼저 확인했고, 인접 설비 운전자에게도 작업 사실을 통보했다. ");
                sb.append("점검에는 총 %d명이 투입되어 %d시간이 소요되었다. ".formatted(workers, hours));
                sb.append("작업 후 공구와 부품 잔재를 회수했고 누락이 없음을 두 사람이 교차 확인했다. ");
                sb.append("설비 내부에 공구를 남기면 재기동 시 2차 손상으로 이어지므로 이 확인은 생략하지 않는다. ");
                sb.append(FOLLOW_UP[followUp2]);
            }
            return sb.toString();
        }

        /**
         * 원인 문장을 붙인다. 조사(이/가)를 받침으로 골라 "절연 저하이(가)" 같은 표기를 피한다.
         *
         * <p>사람이 안 쓰는 표기가 섞이면 그 자체가 문서의 특징이 되어 임베딩이 내용이 아니라
         * <b>생성 티</b>를 학습한 쪽으로 쏠릴 수 있다.
         */
        private void appendCauseKo(StringBuilder sb, String prefix, int causeIndex) {
            String word = CAUSE[causeIndex];
            sb.append("%s%s%s 원인으로 확인되었다. "
                    .formatted(prefix, word, hasFinalConsonant(word) ? "이" : "가"));
        }

        /** 한글 음절의 받침 유무. 유니코드 한글은 (코드 - 0xAC00) % 28 == 0 이면 받침이 없다. */
        private boolean hasFinalConsonant(String word) {
            char last = word.charAt(word.length() - 1);
            if (last < 0xAC00 || last > 0xD7A3) {
                return true; // 한글이 아니면 보수적으로 '이'를 붙인다
            }
            return (last - 0xAC00) % 28 != 0;
        }

        // --- 베트남어 ---
        //
        // 베트남어에는 조사가 없어 한국어의 받침 처리에 대응하는 것이 없다.
        // 대신 어순이 다르다 -- 수식어가 명사 뒤에 오므로 "2호기 냉각 팬"이
        // "quạt làm mát số 2"가 된다. 슬롯을 그대로 끼워 넣으면 어순이 깨진다.

        String subjectVi() {
            return "%s số %d %s".formatted(EQUIPMENT_VI[equipment], unit, PART_VI[part]);
        }

        String titleVi() {
            return "%s %s".formatted(subjectVi(), unit % 2 == 0 ? "xử lý bất thường" : "kết quả kiểm tra");
        }

        String bodyVi() {
            StringBuilder sb = new StringBuilder();
            sb.append("Tại %s, %s. ".formatted(subjectVi(), SYMPTOM_VI[symptom]));
            sb.append("Kết quả kiểm tra xác nhận nguyên nhân là %s. ".formatted(CAUSE_VI[cause]));
            sb.append("%s. ".formatted(capitalize(ACTION_VI[action])));
            sb.append("Sau khi xử lý đã chạy thử %d phút và xác nhận hoạt động bình thường. "
                    .formatted(trialMinutes));
            sb.append("Giá trị đo là %d.%d, nằm trong phạm vi tiêu chuẩn. "
                    .formatted(measuredInt, measuredFrac));
            sb.append(FOLLOW_UP_VI[followUp]);

            if (longForm) {
                sb.append(" Ngoài ra đã kiểm tra thêm hệ thống liền kề. ");
                sb.append("Tại %s số %d %s cũng %s. ".formatted(
                        EQUIPMENT_VI[equipment], unit, PART_VI[part2], SYMPTOM_VI[symptom2]));
                sb.append("Ở phía này, kết quả kiểm tra xác nhận nguyên nhân là %s. "
                        .formatted(CAUSE_VI[cause2]));
                sb.append("%s. ".formatted(capitalize(ACTION_VI[action2])));
                sb.append("Cả hai trường hợp đều có vẻ cùng nhóm nguyên nhân nên cần kiểm tra "
                        + "theo hệ thống chứ không xử lý riêng lẻ. ");
                sb.append("Kiểm tra lịch sử cho thấy trong %d tháng qua đã có %d trường hợp tương tự "
                        + "ở cùng hệ thống. ".formatted(months, similarCases));
                sb.append("Khi đó cũng chỉ xử lý tạm thời nên có vẻ nguyên nhân gốc vẫn còn. ");
                sb.append("Đã trao đổi với người phụ trách thiết bị và quyết định đưa hệ thống này "
                        + "vào hạng mục kiểm tra định kỳ. ");
                sb.append("Thời gian đặt phụ tùng thay thế kéo dài %d tuần nên cần chuẩn bị sẵn "
                        + "phụ tùng dự phòng. ".formatted(leadWeeks));
                sb.append("Về biện pháp an toàn, đã ngắt nguồn điện và gắn biển khóa trước khi tiến hành. ");
                sb.append("Vì có thể còn áp suất dư nên đã xả trước, đồng thời thông báo cho "
                        + "người vận hành thiết bị bên cạnh. ");
                sb.append("Tổng cộng %d người tham gia kiểm tra và mất %d giờ. ".formatted(workers, hours));
                sb.append("Sau khi làm việc đã thu hồi dụng cụ và phụ tùng thừa, hai người kiểm tra "
                        + "chéo xác nhận không thiếu sót. ");
                sb.append("Để quên dụng cụ bên trong thiết bị sẽ gây hư hỏng thứ cấp khi khởi động lại "
                        + "nên không bỏ qua bước xác nhận này. ");
                sb.append(FOLLOW_UP_VI[followUp2]);
            }
            return sb.toString();
        }

        /** ACTION_VI는 문장 중간에도 쓸 수 있게 소문자로 두었다. 문장을 시작할 때만 올린다. */
        private String capitalize(String s) {
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }
}
