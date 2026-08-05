package com.DOCKin.rag.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Random;

/**
 * 대량 작업일지 코퍼스 생성기 (백로그 P0-13-4).
 *
 * <h3>이것은 테스트가 아니라 도구다</h3>
 * JUnit에 얹은 이유는 프로젝트에 이미 같은 형태의 도구가 둘 있고
 * ({@link BruteForceSearchBenchmarkTest}, {@code SentenceLookbackMeasurementTest})
 * 별도 실행 진입점을 새로 만드는 것보다 기존 관례를 따르는 편이 낫기 때문이다.
 * 환경변수 {@code SEED_CORPUS_SIZE}가 없으면 <b>실패가 아니라 skip</b> 된다.
 *
 * <pre>
 *   DB_PASSWORD=... SEED_CORPUS_SIZE=20000 ./gradlew test --tests "*CorpusSeedGeneratorTest"
 * </pre>
 *
 * <h3>왜 필요한가 -- R__seed_sample.sql로는 안 되는 것</h3>
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
 * <h3>한계 -- 이것은 "실데이터"가 아니다</h3>
 * 문장을 슬롯 조합으로 만들기 때문에 <b>실제 작업일지보다 어휘가 좁고 서로 더 비슷하다.</b>
 * 임베딩 공간에서 실제보다 조밀하게 뭉칠 가능성이 높고, 그러면 최근접 이웃 사이의 거리 차가
 * 작아져 <b>recall이 실제보다 낮게(=보수적으로) 나오는 방향</b>으로 치우친다.
 *
 * <p>따라서 여기서 얻은 recall 수치는 <b>하한선으로만 읽어야 하며</b>,
 * "HNSW를 켜도 되는가"의 판단 근거로는 쓸 수 있어도 "recall이 정확히 몇 퍼센트인가"의
 * 답으로 인용해서는 안 된다. 그 수치는 실제 작업일지가 쌓인 뒤에만 나온다.
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

    /** 생성 코퍼스의 소유자. R__seed_sample.sql이 만든 실제 사용자들에게 분배한다. */
    private static final String[] OWNERS = {"worker01", "worker02", "worker03"};

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
            assertSeedUsersExist(conn);

            long start = System.currentTimeMillis();
            int removed = removePreviouslyGenerated(conn);
            int inserted = generate(conn, size);
            int orphans = removeOrphanChunks(conn);

            System.out.println();
            System.out.println("=== 코퍼스 생성 완료 ===");
            System.out.printf("이전 생성분 삭제 : %,d건%n", removed);
            System.out.printf("신규 생성        : %,d건%n", inserted);
            System.out.printf("고아 청크 정리   : %,d건%n", orphans);
            System.out.printf("소요             : %,dms%n", System.currentTimeMillis() - start);
            System.out.println();
            System.out.println("다음 단계 - 임베딩을 만들려면 색인을 돌려야 합니다:");
            System.out.println("  SPRING_PROFILES_ACTIVE=seed RAG_INDEXING_ON_STARTUP=true ./gradlew bootRun");
            System.out.println();
        } catch (SQLException e) {
            Assumptions.abort("PostgreSQL(localhost:5432)에 접속할 수 없어 건너뜁니다: " + e.getMessage());
        }
    }

    /**
     * 시드 사용자가 없으면 멈춘다.
     *
     * <p>{@code work_logs.user_id}는 {@code users}를 참조하는 FK다. 없는 사용자로 넣으면
     * 어차피 FK 위반으로 실패하지만, 그때 나오는 것은 제약 이름이 박힌 SQL 오류라
     * <b>"seed 프로파일로 앱을 한 번 띄워 시드를 넣어야 한다"는 진짜 원인이 드러나지 않는다.</b>
     */
    private void assertSeedUsersExist(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM users WHERE user_id = ANY (?)")) {
            ps.setArray(1, conn.createArrayOf("varchar", OWNERS));
            try (var rs = ps.executeQuery()) {
                rs.next();
                int found = rs.getInt(1);
                if (found < OWNERS.length) {
                    throw new IllegalStateException(
                            "시드 사용자가 없습니다(%d/%d). 먼저 seed 프로파일로 앱을 띄워 R__seed_sample.sql을 적용하세요: "
                                    .formatted(found, OWNERS.length)
                                    + "SPRING_PROFILES_ACTIVE=seed ./gradlew bootRun");
                }
            }
        }
    }

    /**
     * 이전 생성분을 지운다. 지우지 않고 또 넣으면 실행할 때마다 코퍼스가 불어나
     * <b>"몇 건에서 잰 수치인가"를 알 수 없게 된다.</b>
     */
    private int removePreviouslyGenerated(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM work_logs WHERE title LIKE ?")) {
            ps.setString(1, MARKER + "%");
            return ps.executeUpdate();
        }
    }

    /**
     * 원본이 사라진 청크를 지운다.
     *
     * <p><b>이 정리를 빠뜨리면 조용히 틀린다.</b> {@code document_chunks}는 여러 원본 테이블을
     * {@code (source_type, source_id)}로 다형 참조하므로 <b>FK가 없고, 따라서 CASCADE도 없다.</b>
     * 위에서 {@code work_logs}를 지워도 청크는 그대로 남는다.
     *
     * <p>남은 청크는 여전히 검색에 걸리고 챗봇 근거로 주입된다 -- 원본이 없는 근거이므로
     * 화면에서 추적할 수도 없다. 게다가 recall을 측정할 때 정답 집합에 없는 후보가 섞여
     * 수치를 오염시킨다.
     */
    private int removeOrphanChunks(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            return st.executeUpdate("""
                    DELETE FROM document_chunks
                     WHERE source_type = 'WORK_LOG'
                       AND source_id NOT IN (SELECT log_id FROM work_logs)
                    """);
        }
    }

    /**
     * 작업일지를 생성한다.
     *
     * <p>{@code log_id}를 명시하지 않고 IDENTITY에 맡긴다 -- 시퀀스가 정상적으로 올라가므로
     * {@code R__seed_sample.sql}이 필요로 했던 {@code setval} 보정이 여기서는 필요 없다.
     */
    private int generate(Connection conn, int size) throws SQLException {
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

        conn.setAutoCommit(false);
        int inserted = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
                Subject subject = Subject.of(random);

                ps.setString(1, MARKER + title(random, subject));
                ps.setString(2, body(random, subject));
                ps.setTimestamp(3, Timestamp.valueOf(writtenAt));
                ps.setTimestamp(4, Timestamp.valueOf(writtenAt));
                ps.setString(5, OWNERS[random.nextInt(OWNERS.length)]);
                // 장비는 R__seed_sample.sql이 만든 1~5번. 일부는 장비 없는 일지다.
                int equipment = random.nextInt(6);
                if (equipment == 0) {
                    ps.setNull(6, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(6, equipment);
                }
                ps.addBatch();

                if ((i + 1) % 1000 == 0) {
                    ps.executeBatch();
                    conn.commit();
                    inserted += 1000;
                    System.out.printf("  생성 중... %,d / %,d%n", inserted, size);
                }
            }
            ps.executeBatch();
            conn.commit();
            inserted = size;
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
    // ------------------------------------------------------------------

    private static final String[] EQUIPMENT = {
            "CO2 용접기", "겐트리 크레인", "무인 도장설비", "플라즈마 절단기", "고소작업대",
            "유압 프레스", "블라스팅 장비", "이송 컨베이어", "공기압축기", "집진 설비",
            "지게차", "발전기"
    };
    private static final String[] PART = {
            "송급 롤러", "유압 실린더", "제어 패널", "베어링부", "감속기",
            "배관 플랜지", "전동기 축", "냉각 팬", "안전 스위치", "케이블 그랜드"
    };
    private static final String[] SYMPTOM = {
            "이상 진동이 발생했다", "간헐적으로 정지했다", "온도가 규정치를 넘었다",
            "누유가 확인되었다", "소음이 평소보다 커졌다", "출력이 불안정했다",
            "경보가 반복 발생했다", "동작이 지연되었다", "압력이 유지되지 않았다",
            "표시값이 튀는 현상이 있었다", "기동에 실패했다", "과전류로 차단되었다"
    };
    private static final String[] CAUSE = {
            "체결 볼트 이완", "윤활 부족", "필터 막힘", "센서 접점 불량", "절연 저하",
            "개스킷 경화", "베어링 마모", "냉각수 부족", "제어 파라미터 오설정", "이물질 유입"
    };
    private static final String[] ACTION = {
            "규정 토크로 재체결했다", "그리스를 보충했다", "필터를 교체했다",
            "접점을 청소하고 재결선했다", "절연 저항을 측정하고 케이블을 교체했다",
            "개스킷을 신품으로 교체했다", "베어링을 교체하고 정렬을 재조정했다",
            "냉각수를 보충하고 누설 여부를 확인했다", "파라미터를 기준값으로 되돌렸다",
            "내부를 분해 청소했다", "임시 조치 후 정비팀에 이관했다", "예비품으로 교체했다"
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

    /** 한 작업일지가 다루는 대상. 제목과 본문이 같은 것을 가리키게 하려고 먼저 뽑아 넘긴다. */
    private record Subject(String equipment, int unit, String part) {
        static Subject of(Random r) {
            return new Subject(EQUIPMENT[r.nextInt(EQUIPMENT.length)], 1 + r.nextInt(9),
                    PART[r.nextInt(PART.length)]);
        }

        String describe() {
            return "%s %d호기 %s".formatted(equipment, unit, part);
        }
    }

    private String title(Random r, Subject subject) {
        return "%s %s".formatted(subject.describe(), r.nextBoolean() ? "이상 조치" : "점검 결과");
    }

    private String body(Random r, Subject subject) {
        StringBuilder sb = new StringBuilder();
        sb.append("%s에서 %s. ".formatted(subject.describe(), pick(r, SYMPTOM)));
        appendCause(sb, r, "점검 결과 ");
        // ACTION은 마침표로 끝나지 않는다. 여기서 붙이지 않으면 다음 문장과 이어 붙어
        // 종결 부호가 사라진다 -- FixedSizeChunkingStrategy는 종결 부호로 문장 경계를 찾으므로
        // 그 자리는 경계를 못 찾고 강제 절단된다(SENTENCE_LOOKBACK 실측 6-6의 STT 사례와 같다).
        sb.append("%s. ".formatted(pick(r, ACTION)));
        sb.append("조치 후 %d분간 시운전하여 정상 동작을 확인했다. ".formatted(10 + r.nextInt(50)));
        // 숫자 뒤 조사는 읽는 방식에 따라 로/으로가 갈린다("67.2로" / "16으로"). 규칙을 구현하느니
        // 조사를 안 쓰는 문형으로 피한다 -- 이 문장은 2,000건 전부에 들어가므로 어색한 표기가
        // 있으면 코퍼스 전체에 같은 흔적이 박힌다.
        sb.append("측정값은 %d.%d이며 기준 범위 이내였다. ".formatted(10 + r.nextInt(80), r.nextInt(10)));
        sb.append(pick(r, FOLLOW_UP));

        // 다섯 건 중 하나는 500자(TARGET_CHARS)를 넘겨 2청크 이상이 되게 한다.
        //
        // 처음에는 문장 몇 개만 덧붙였는데 최대가 334자에 그쳐 전부 1청크였다. 그러면
        // RetrievalService의 "원본 문서당 최대 2청크" 제한이 한 번도 실행되지 않고,
        // 청크 인덱스가 1 이상인 행도 생기지 않아 재색인 경로의 일부가 검증되지 않는다.
        if (r.nextInt(5) == 0) {
            sb.append(" 추가로 인접 계통도 함께 점검했다. ");
            sb.append("%s %d호기 %s에서도 %s. ".formatted(
                    subject.equipment(), subject.unit(), pick(r, PART), pick(r, SYMPTOM)));
            appendCause(sb, r, "이쪽은 ");
            sb.append("%s. ".formatted(pick(r, ACTION)));
            sb.append("두 건 모두 같은 원인 계열로 보이므로 개별 조치가 아니라 계통 단위 점검이 필요하다. ");
            sb.append("이력을 확인해보니 지난 %d개월 사이 같은 계통에서 %d건의 유사 사례가 있었다. "
                    .formatted(1 + r.nextInt(11), 2 + r.nextInt(5)));
            sb.append("당시에도 임시 조치로 마무리되어 근본 원인이 남아 있었던 것으로 보인다. ");
            sb.append("설비 담당과 협의해 정기 점검 항목에 이 계통을 포함시키기로 했다. ");
            sb.append("교체 부품의 조달 기간이 %d주로 길어 예비품을 미리 확보해 두어야 한다. "
                    .formatted(1 + r.nextInt(6)));
            sb.append("작업 중 안전 조치로는 전원을 차단하고 잠금 표지를 부착한 뒤 진행했다. ");
            sb.append("잔압이 남아 있을 수 있어 배출을 먼저 확인했고, 인접 설비 운전자에게도 작업 사실을 통보했다. ");
            sb.append("점검에는 총 %d명이 투입되어 %d시간이 소요되었다. "
                    .formatted(2 + r.nextInt(3), 1 + r.nextInt(6)));
            sb.append("작업 후 공구와 부품 잔재를 회수했고 누락이 없음을 두 사람이 교차 확인했다. ");
            sb.append("설비 내부에 공구를 남기면 재기동 시 2차 손상으로 이어지므로 이 확인은 생략하지 않는다. ");
            sb.append(pick(r, FOLLOW_UP));
        }
        return sb.toString();
    }

    /**
     * 원인 문장을 붙인다. 조사(이/가)를 받침으로 골라 "절연 저하이(가)" 같은 표기를 피한다.
     *
     * <p>사람이 안 쓰는 표기가 섞이면 그 자체가 문서의 특징이 되어 임베딩이 내용이 아니라
     * <b>생성 티</b>를 학습한 쪽으로 쏠릴 수 있다. 코퍼스의 목적이 실제 분포를 흉내내는 것이므로
     * 이런 흔적은 없는 편이 낫다.
     */
    private void appendCause(StringBuilder sb, Random r, String prefix) {
        String cause = pick(r, CAUSE);
        sb.append("%s%s%s 원인으로 확인되었다. ".formatted(prefix, cause, hasFinalConsonant(cause) ? "이" : "가"));
    }

    /** 한글 음절의 받침 유무. 유니코드 한글은 (코드 - 0xAC00) % 28 == 0 이면 받침이 없다. */
    private boolean hasFinalConsonant(String word) {
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return true; // 한글이 아니면 보수적으로 '이'를 붙인다
        }
        return (last - 0xAC00) % 28 != 0;
    }

    private String pick(Random r, String[] pool) {
        return pool[r.nextInt(pool.length)];
    }
}
