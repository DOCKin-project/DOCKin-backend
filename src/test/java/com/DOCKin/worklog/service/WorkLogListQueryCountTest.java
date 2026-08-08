package com.DOCKin.worklog.service;

import com.DOCKin.global.testsupport.PostgresTestSupport;
import com.DOCKin.worklog.dto.WorkLogDto;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A5 실측 (1/2): <b>목록 API가 한 페이지에 쿼리를 몇 개 쓰는가.</b>
 *
 * <h3>왜 이것부터 재는가 — 로드맵 2-3의 진단은 "한 쿼리가 얼마나 느린가"만 본다</h3>
 * 로드맵 2-3은 {@code work_logs}에 인덱스가 PK 하나뿐이라는 데서 출발해
 * 풀스캔 / COUNT / OFFSET / 정렬을 지목했다. 전부 <b>쿼리 하나의 비용</b> 이야기다.
 * 그 앞에 <b>쿼리가 몇 개 나가는가</b>가 있고, 이쪽은 인덱스로 줄어들지 않는다.
 *
 * <p>측정을 둘로 나눈 이유이기도 하다. 쿼리 개수는 <b>규모와 무관</b>해서 60건짜리 표본으로
 * 정확히 셀 수 있다. 그래서 이 테스트는 <b>CI에서 돈다.</b> 100만 건을 적재해야 하는
 * 지연시간 쪽은 {@code WorkLogListBenchmarkTest}가 따로 맡는다.
 *
 * <h3>측정 전 가설 — 그리고 그것이 틀린 지점</h3>
 * {@link WorkLogDto#from}이 지연 로딩을 세 번 건드린다고 읽었다.
 * <pre>
 *   entity.getMember().getUserId()          -- @ManyToOne(LAZY)
 *   entity.getEquipment().getEquipmentId()  -- @ManyToOne(LAZY)
 *   entity.getImages().stream()             -- @OneToMany(LAZY)
 * </pre>
 * 그러면 페이지 크기 20에 지연 로딩이 60번이어야 한다. <b>실측은 20번이었다.</b>
 *
 * <p>앞의 둘이 <b>식별자만 읽기</b> 때문이다. {@code getUserId()}와 {@code getEquipmentId()}는
 * 각 엔티티의 {@code @Id}이고, 지연 프록시는 <b>식별자를 이미 갖고 있다</b> —
 * FK 컬럼이 {@code work_logs} 행 안에 있으므로 프록시를 만들 때 함께 채워진다.
 * 그래서 이 두 줄은 프록시를 초기화하지 않고 쿼리도 내지 않는다.
 * {@code getMember().getName()}이었다면 얘기가 달라진다.
 *
 * <p>아래 {@code 장비와 작성자가 행마다 모두 다른} 표본이 그 주장을 증명한다 —
 * 프록시가 초기화된다면 행마다 두 개씩 더 나가야 하는데, 늘어나지 않는다.
 *
 * <h3>남는 것 — 이미지 컬렉션은 진짜 N+1이다</h3>
 * {@code getImages()}는 컬렉션이라 식별자로 답할 수 없다. <b>행마다 정확히 한 번</b> 나간다.
 * 이미지가 <b>없는 행도</b> 나간다 — 비어 있다는 것을 확인하려면 조회해야 하기 때문이다.
 *
 * <h3>세는 주체를 Hibernate로 둔다</h3>
 * {@link Statistics#getPrepareStatementCount()}가 센 값을 쓴다. 코드를 읽고 센 수에는
 * 읽는 사람의 가정이 섞이고, 이 측정에서 실제로 그 가정이 틀렸다.
 * {@code HibernateBatchInsertVerificationTest}가 시퀀스 호출을 {@code pg_stat_statements}로
 * 센 것과 같은 이유다.
 *
 * <h3>이 테스트는 결함을 고정한다</h3>
 * 아래 단언은 <b>현재 상태</b>를 식으로 못 박는다. N+1을 고치면 이 테스트가 실패해야 하고,
 * 그때 {@code docs/WORK-BACKLOG.md}의 A5 항목과 함께 갱신하는 것이 맞다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 이것이 없으면 Statistics가 전부 0을 돌려주고 테스트는 아무것도 세지 않은 채 통과한다.
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // 배치 페치가 켜지면 컬렉션 초기화가 묶여 N+1의 크기가 달라진다.
        // 운영(application.properties)에 이 값이 없으므로 꺼진 상태를 명시한다 --
        // 나중에 누가 운영에 켜면 이 테스트가 실패해서 알려주는 편이 낫다.
        "spring.jpa.properties.hibernate.default_batch_fetch_size=-1"
})
class WorkLogListQueryCountTest extends PostgresTestSupport {

    /** 측정 대상 구역. 다른 테스트가 만든 사용자와 섞이지 않도록 이 테스트만 쓰는 이름을 쓴다. */
    private static final String AREA = "A5측정구역";

    /** 다른 작업자 조회용. 같은 구역이어야 조회가 허용되므로 열람자와 대상을 함께 둔다. */
    private static final String AREA_OTHER = "A5타인조회구역";

    /** 장비가 없는 행을 격리하는 구역. 측정용 페이지에 섞이면 아래 NPE로 측정이 중단된다. */
    private static final String AREA_NULL_EQUIPMENT = "A5장비없음구역";

    /**
     * 측정 구역의 작성자 수. <b>한 페이지(20)를 채우고도 남게</b> 둔다.
     *
     * <p>작성자가 적으면 같은 프록시를 여러 행이 공유해서, 프록시가 초기화되더라도
     * 쿼리가 행 수만큼 늘지 않는다. 그러면 "식별자만 읽어서 안 나간 것"인지
     * "공유해서 안 나간 것"인지 구별할 수 없다.
     */
    private static final int MEMBERS = 25;

    private static final int LOGS = 60;

    /** 다른 작업자 조회 대상이 가진 일지 수. 한 페이지를 채워야 행당 비용이 보인다. */
    private static final int OTHER_USER_LOGS = 25;

    /** 이미지가 붙는 일지의 비율(1/N). 전부 붙이면 "이미지가 없어도 쿼리가 나가는가"를 못 본다. */
    private static final int IMAGE_EVERY = 3;

    /** 이 테스트가 만든 행만 고르는 접두사. */
    private static final String PREFIX = "a5";

    /** 키워드 검색이 이 테스트의 행만 잡도록 하는 표식. */
    private static final String KEYWORD = "A5측정본문";

    @Autowired
    private WorkLogsService workLogsService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        seed();
    }

    /**
     * 세 목록 엔드포인트가 모두 <b>고정 비용 + 행당 1</b>이라는 것을 고정한다.
     *
     * <p>고정 비용이 엔드포인트마다 다른 것이 그 자체로 정보다.
     * <ul>
     *   <li>전체 목록 4개 — 내 정보 / <b>구역 사용자 전체</b> / 본문 / COUNT</li>
     *   <li>타인 목록 4개 — 내 정보 / 대상자 정보 / 본문 / COUNT</li>
     *   <li>키워드 검색 2개 — 본문 / COUNT</li>
     * </ul>
     * 전체 목록의 두 번째가 {@code findByShipYardArea}이고, 이것은 <b>구역 사용자를 전부</b>
     * 메모리로 올린 뒤 {@code IN} 절에 통째로 넣는다. 쿼리 <b>개수</b>로는 1이라 여기서는
     * 작아 보이지만, 규모가 커지면 이 한 줄이 가장 비싸진다 — 그쪽은 2/2 벤치마크가 잰다.
     */
    @Test
    @DisplayName("목록 API 쿼리 수 = 고정 비용 + 행당 1 (이미지 컬렉션)")
    void 목록_쿼리_수() {
        Measurement all10 = measure("전체 목록", 10,
                () -> workLogsService.readWorklog(user(0), PageRequest.of(0, 10)));
        Measurement all20 = measure("전체 목록", 20,
                () -> workLogsService.readWorklog(user(0), PageRequest.of(0, 20)));
        Measurement other = measure("타인 목록", 20,
                () -> workLogsService.readOtherWorklog(otherViewer(), otherTarget(), PageRequest.of(0, 20)));
        Measurement search = measure("키워드 검색", 20,
                () -> workLogsService.searchByKeyword(KEYWORD, PageRequest.of(0, 20)));

        print(all10, all20, other, search);

        assertEquals(20, all20.rows(), "표본이 한 페이지를 채우지 못하면 행당 비용을 볼 수 없다");
        assertEquals(20, other.rows(), "타인 목록 표본이 한 페이지를 채우지 못했다");
        assertEquals(20, search.rows(), "키워드 검색 표본이 한 페이지를 채우지 못했다");

        assertEquals(4 + 10, all10.queries(), explain("전체 목록(10)", all10));
        assertEquals(4 + 20, all20.queries(), explain("전체 목록(20)", all20));
        assertEquals(4 + 20, other.queries(), explain("타인 목록(20)", other));
        assertEquals(2 + 20, search.queries(), explain("키워드 검색(20)", search));

        // 행당 1이 어디서 나오는지까지 고정한다. 개수만 고정하면 다음 사람이 다시 세야 한다.
        assertEquals(all20.rows(), all20.collectionFetches(),
                "행당 1의 출처는 이미지 컬렉션이어야 한다");
    }

    /**
     * 위 식의 근거를 직접 확인한다 — <b>@ManyToOne 둘은 쿼리를 내지 않는다.</b>
     *
     * <p>표본은 작성자와 장비가 <b>행마다 모두 다르게</b> 만들어져 있다. 프록시가 초기화된다면
     * 행마다 두 개가 더 나가 페이지 20에서 64개가 되어야 한다. 위 테스트가 24를 단언하므로
     * 이 테스트는 그 <b>전제</b>(표본이 정말 전부 다른가)를 지킨다 — 표본이 공유 상태로
     * 되돌아가면 위 숫자는 같은 값을 유지하면서 <b>의미만 잃는다.</b> 조용히 틀리는 쪽이다.
     */
    @Test
    @DisplayName("첫 페이지의 작성자와 장비가 행마다 모두 다르다 - 위 측정의 전제")
    void 표본_전제_검사() {
        // 첫 페이지에 해당하는 20건을 뽑아 그 안의 서로 다른 값 개수를 센다.
        // 정렬을 log_id로 두는 것은 findByMemberIn이 정렬 없는 Pageable을 받아
        // 삽입 순서로 돌려주기 때문이다(측정 호출도 정렬 없는 PageRequest를 쓴다).
        Sample sample = jdbc.queryForObject("""
                SELECT COUNT(*), COUNT(DISTINCT user_id), COUNT(DISTINCT equipment_id)
                  FROM (SELECT log_id, user_id, equipment_id
                          FROM work_logs
                         WHERE user_id LIKE ? AND title LIKE 'A5측정%'
                         ORDER BY log_id
                         LIMIT 20) first_page
                """,
                (rs, n) -> new Sample(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
                PREFIX + "%");

        assertEquals(20, sample.rows(), "첫 페이지 표본이 20건이 아니다");
        assertEquals(20, sample.distinctUsers(), "작성자가 겹치면 프록시를 공유해 측정이 무의미해진다");
        assertEquals(20, sample.distinctEquipment(), "장비가 겹치면 프록시를 공유해 측정이 무의미해진다");
    }

    /**
     * 측정 중에 나온 것 — <b>장비 없는 작업일지가 하나라도 있으면 그 페이지 전체가 500이 된다.</b>
     *
     * <p>{@code WorkLogDto.from}이 {@code entity.getEquipment().getEquipmentId()}를 무조건 부른다.
     * {@code equipment_id}는 <b>nullable</b>이고({@code V2__baseline_existing_tables.sql}),
     * {@code @ManyToOne(LAZY)}는 FK가 NULL이면 프록시가 아니라 <b>null을 넣는다.</b>
     * 위에서 "식별자만 읽으니 안전하다"고 한 그 접근이, NULL 앞에서는 반대로 터진다.
     *
     * <p>생성 API로는 이 상태가 만들어지지 않는다 — {@code createWorklog}가
     * {@code equipmentRepository.findById(...)}로 장비를 필수로 요구한다. 그래서
     * <b>API만 두드려서는 영영 드러나지 않는다.</b> 반면 컬럼은 NULL을 허용하고,
     * 측정용 코퍼스 생성기는 여섯 건 중 하나를 장비 없이 넣는다(실제로 현재 로컬 DB에 3,331건 있다).
     * <b>스키마가 허용하는 상태를 코드가 가정으로 배제하고 있다</b> — 이 저장소가 반복해서
     * 잡아 온 "선언과 실제의 불일치"와 같은 부류다.
     *
     * <p>영향 범위가 행 하나가 아니라 <b>페이지</b>라는 점이 핵심이다. {@code Page.map}은
     * 한 행에서 터지면 거기서 멈추므로, 장비 없는 일지 하나가 목록 전체를 못 보게 만든다.
     */
    @Test
    @DisplayName("장비 없는 일지가 섞이면 목록 전체가 NPE로 죽는다")
    void 장비가_없으면_목록이_죽는다() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> workLogsService.readWorklog(nullEquipmentUser(), PageRequest.of(0, 20)));

        System.out.println();
        System.out.println("=== 장비 없는 일지 1건이 섞인 페이지 ===");
        System.out.println("  결과 : " + e.getClass().getSimpleName());
        System.out.println("  지점 : " + firstProjectFrame(e));
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 측정
    // ------------------------------------------------------------------

    private record Measurement(String name, int pageSize, long rows, long queries,
                               long entityLoads, long collectionFetches) {}

    /** 첫 페이지 표본의 형태. 세 값을 한 쿼리로 가져와야 서로 다른 20건을 본 것이 보장된다. */
    private record Sample(int rows, int distinctUsers, int distinctEquipment) {}

    /**
     * 한 번 호출하고 그동안 나간 JDBC 문장 수를 센다.
     *
     * <p><b>{@code clear()}를 호출 직전에 둔다.</b> 통계는 SessionFactory 단위로 누적되므로
     * 앞선 호출의 수가 섞이면 배수가 실제보다 크게 나온다.
     */
    private Measurement measure(String name, int pageSize,
                                java.util.function.Supplier<Page<WorkLogDto>> call) {
        statistics.clear();
        Page<WorkLogDto> page = call.get();
        return new Measurement(name, pageSize, page.getNumberOfElements(),
                statistics.getPrepareStatementCount(),
                statistics.getEntityLoadCount(),
                statistics.getCollectionFetchCount());
    }

    private String explain(String label, Measurement m) {
        return """
                %s의 쿼리 수가 예상과 다르다: %d개 (행 %d / 컬렉션 페치 %d).
                줄었다면 N+1이 해소된 것이므로 이 테스트와 docs/WORK-BACKLOG.md의 A5를 함께 갱신하라.
                늘었다면 매핑이 지연 로딩을 하나 더 건드리기 시작한 것이다."""
                .formatted(label, m.queries(), m.rows(), m.collectionFetches());
    }

    private void print(Measurement... measurements) {
        System.out.println();
        System.out.println("=== A5 (1/2) 목록 API 한 페이지당 쿼리 수 ===");
        System.out.printf("%-11s | %-11s | %-7s | %-9s | %-9s | %-11s | %-11s%n",
                "엔드포인트", "페이지 크기", "반환 행", "JDBC 문장", "고정 비용", "컬렉션 페치", "엔티티 로드");
        System.out.println("-".repeat(96));
        for (Measurement m : measurements) {
            System.out.printf("%-11s | %-11d | %-7d | %-9d | %-9d | %-11d | %-11d%n",
                    m.name(), m.pageSize(), m.rows(), m.queries(),
                    m.queries() - m.rows(), m.collectionFetches(), m.entityLoads());
        }
        System.out.println();
        System.out.println("  고정 비용 = 쿼리 수 - 행 수. 행당 정확히 1개가 이미지 컬렉션이다.");
        System.out.println("  @ManyToOne 둘(member/equipment)은 식별자만 읽으므로 쿼리를 내지 않는다.");
        System.out.println();
    }

    /** 스택에서 이 프로젝트의 첫 프레임. 프레임워크 프레임만 찍히면 어디를 고칠지 알 수 없다. */
    private String firstProjectFrame(Throwable t) {
        for (StackTraceElement frame : t.getStackTrace()) {
            if (frame.getClassName().startsWith("com.DOCKin")) {
                return frame.toString();
            }
        }
        return "(com.DOCKin 프레임 없음)";
    }

    // ------------------------------------------------------------------
    // 표본
    // ------------------------------------------------------------------

    private String user(int index) {
        return "%su%02d".formatted(PREFIX, index);
    }

    private String otherViewer() {
        return PREFIX + "ov";
    }

    private String otherTarget() {
        return PREFIX + "ot";
    }

    private String nullEquipmentUser() {
        return PREFIX + "null";
    }

    /**
     * 표본을 넣는다. <b>JPA가 아니라 JDBC로 넣는 이유</b>가 둘이다.
     * <ul>
     *   <li>{@code equipment_id}가 NULL인 행을 만들어야 하는데 생성 서비스가 그것을 막는다</li>
     *   <li>영속성 컨텍스트에 엔티티가 남아 있으면 뒤이은 조회가 <b>1차 캐시에서 나와</b>
     *       쿼리가 아예 안 나간다 — 세려는 대상이 사라진다</li>
     * </ul>
     */
    private void seed() {
        jdbc.update("DELETE FROM work_log_images WHERE work_log_id IN "
                + "(SELECT log_id FROM work_logs WHERE user_id LIKE ?)", PREFIX + "%");
        jdbc.update("DELETE FROM work_logs WHERE user_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM users WHERE user_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM equipment WHERE nfc_tag LIKE ?", PREFIX + "-nfc-%");

        for (int i = 0; i < MEMBERS; i++) {
            insertUser(user(i), AREA);
        }
        insertUser(otherViewer(), AREA_OTHER);
        insertUser(otherTarget(), AREA_OTHER);
        insertUser(nullEquipmentUser(), AREA_NULL_EQUIPMENT);

        // 장비를 일지 수만큼 만든다. 첫 페이지에서 장비가 겹치면 프록시를 공유하게 되어
        // "식별자만 읽어 쿼리가 안 나간다"는 주장을 이 표본으로는 확인할 수 없다.
        List<Long> equipmentIds = new ArrayList<>();
        for (int i = 0; i < LOGS; i++) {
            equipmentIds.add(insertEquipment(i));
        }

        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 9, 0);
        for (int i = 0; i < LOGS; i++) {
            // 작성자를 순서대로 배정한다. MEMBERS가 페이지 크기보다 크므로 첫 페이지는 전부 다른 사람이다.
            Long logId = insertWorkLog("A5측정제목 %03d".formatted(i),
                    "%s %03d - 목록 API 쿼리 수 측정용 표본이다.".formatted(KEYWORD, i),
                    base.plusMinutes(i), user(i % MEMBERS), equipmentIds.get(i));

            if (i % IMAGE_EVERY == 0) {
                jdbc.update("INSERT INTO work_log_images (image_url, work_log_id) VALUES (?, ?)",
                        "https://example.invalid/a5-%03d.jpg".formatted(i), logId);
            }
        }

        // 타인 조회 대상. 키워드 표식을 빼서 검색 측정에 섞이지 않게 한다.
        for (int i = 0; i < OTHER_USER_LOGS; i++) {
            insertWorkLog("A5타인제목 %03d".formatted(i), "타인 목록 측정용 표본이다.",
                    base.plusMinutes(i), otherTarget(), equipmentIds.get(i));
        }

        // 장비 없는 행. 위 구역들과 분리해야 나머지 측정이 이 행에 걸려 중단되지 않는다.
        insertWorkLog("A5장비없음", "장비를 지정하지 않은 작업일지다.", base, nullEquipmentUser(), null);
    }

    private Long insertWorkLog(String title, String text, LocalDateTime at,
                               String userId, Long equipmentId) {
        return jdbc.queryForObject("""
                INSERT INTO work_logs (title, log_text, created_at, updated_at, user_id, equipment_id)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING log_id
                """, Long.class, title, text, at, at, userId, equipmentId);
    }

    private void insertUser(String userId, String area) {
        jdbc.update("""
                INSERT INTO users (user_id, name, password, role, language_code,
                                   ship_yard_area, remaining_leave_days, tts_enabled, work_shift, created_at)
                VALUES (?, ?, ?, 'USER', 'ko', ?, 15, false, 'MORNING', ?)
                """, userId, userId, "$2a$10$" + "0".repeat(53), area,
                LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    private Long insertEquipment(int index) {
        return jdbc.queryForObject("""
                INSERT INTO equipment (name, qr_code, nfc_tag) VALUES (?, ?, ?)
                RETURNING equipment_id
                """, Long.class,
                "A5측정장비%03d".formatted(index),
                "%s-qr-%03d".formatted(PREFIX, index),
                "%s-nfc-%03d".formatted(PREFIX, index));
    }
}
