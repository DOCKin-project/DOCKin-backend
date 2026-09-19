package com.DOCKin.checklist.repository;

import com.DOCKin.checklist.model.ChecklistRun;
import com.DOCKin.checklist.model.ChecklistRunOutcome;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 검증: {@code findForAdmin}의 JPQL — 날짜 창, 선택 필터 셋의 null 바인딩, 상태의 두 축(열림/결말).
 *
 * <p>목으로는 못 본다. {@code CAST(:x AS ...) IS NULL}이 실제로 "필터 없음"으로 풀리는지, 자정 경계가 포함/제외 어느 쪽인지,
 * {@code JOIN FETCH}가 열림 필터와 같이 돌아가는지는 DB에 물어야 안다. 데이터는 SQL로 넣는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=0"
})
class ChecklistRunRepositoryTest extends ContainerTestSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final LocalDateTime FROM = DAY.atStartOfDay();
    private static final LocalDateTime TO = DAY.plusDays(1).atStartOfDay();

    @Autowired
    private ChecklistRunRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private long craneId;
    private long weldingId;
    private int cranePre;
    private int weldingPre;
    private long u1CraneOpen;
    private long u1CraneDone;
    private long u2CraneAbandoned;
    private long u2WeldingDone;
    private long u1CraneYesterday;

    @BeforeEach
    void seed() {
        user("u1");
        user("u2");
        craneId = equipment("크레인", "nfc-1", "qr-1");
        weldingId = equipment("용접기", "nfc-2", "qr-2");
        cranePre = checklist(craneId, "PRE");
        weldingPre = checklist(weldingId, "PRE");

        u1CraneOpen = run(cranePre, "u1", DAY.atTime(8, 0), null, null);                                   // 열림
        u1CraneDone = run(cranePre, "u1", DAY.atTime(7, 0), DAY.atTime(7, 20), "COMPLETED");                // 같은 날 앞 회차
        u2CraneAbandoned = run(cranePre, "u2", DAY.atTime(0, 0), DAY.atTime(13, 0), "ABANDONED");          // 자정 정각 — 포함
        u2WeldingDone = run(weldingPre, "u2", DAY.atTime(23, 59, 59), DAY.plusDays(1).atTime(0, 5), "COMPLETED");
        u1CraneYesterday = run(cranePre, "u1", DAY.minusDays(1).atTime(23, 59, 59), DAY.atTime(0, 1), "COMPLETED"); // 전날 — 제외
        run(cranePre, "u2", DAY.plusDays(1).atStartOfDay(), null, null);                                    // 다음날 자정 — 제외
    }

    @Test
    @DisplayName("필터 없음 — 그날 시작한 회차 넷, 최신순. 자정 정각은 그날이고 다음날 자정은 아니다")
    void noFilter_dayWindow_newestFirst() {
        Slice<ChecklistRun> found = repository.findForAdmin(FROM, TO, null, null, false, null, PageRequest.of(0, 10));

        assertEquals(List.of(u2WeldingDone, u1CraneOpen, u1CraneDone, u2CraneAbandoned), ids(found));
    }

    @Test
    @DisplayName("장비로 거르면 그 장비의 회차만 — 크레인 셋")
    void byEquipment() {
        Slice<ChecklistRun> found = repository.findForAdmin(FROM, TO, craneId, null, false, null, PageRequest.of(0, 10));

        assertEquals(List.of(u1CraneOpen, u1CraneDone, u2CraneAbandoned), ids(found));
    }

    @Test
    @DisplayName("점검자로 거르면 그 사람의 회차만 — u2 둘")
    void byUser() {
        Slice<ChecklistRun> found = repository.findForAdmin(FROM, TO, null, "u2", false, null, PageRequest.of(0, 10));

        assertEquals(List.of(u2WeldingDone, u2CraneAbandoned), ids(found));
    }

    @Test
    @DisplayName("IN_PROGRESS는 열린 것만(closed_at IS NULL), COMPLETED·ABANDONED는 결말로 — 두 축이 섞이지 않는다")
    void byStatus_twoAxes() {
        assertEquals(List.of(u1CraneOpen),
                ids(repository.findForAdmin(FROM, TO, null, null, true, null, PageRequest.of(0, 10))));
        assertEquals(List.of(u2WeldingDone, u1CraneDone),
                ids(repository.findForAdmin(FROM, TO, null, null, false, ChecklistRunOutcome.COMPLETED, PageRequest.of(0, 10))));
        assertEquals(List.of(u2CraneAbandoned),
                ids(repository.findForAdmin(FROM, TO, null, null, false, ChecklistRunOutcome.ABANDONED, PageRequest.of(0, 10))));
    }

    @Test
    @DisplayName("필터 셋을 같이 — 크레인·u1·COMPLETED는 하나")
    void allFilters() {
        Slice<ChecklistRun> found = repository.findForAdmin(FROM, TO, craneId, "u1", false, ChecklistRunOutcome.COMPLETED, PageRequest.of(0, 10));

        assertEquals(List.of(u1CraneDone), ids(found));
        // JOIN FETCH — 응답이 읽는 셋이 이미 올라와 있다(지연 로딩 없이)
        ChecklistRun run = found.getContent().get(0);
        assertEquals("크레인", run.getChecklist().getEquipment().getName());
        assertEquals("u1", run.getMember().getUserId());
    }

    @Test
    @DisplayName("Slice — 크기 2면 hasNext, 셋째 페이지는 비었고 hasNext=false")
    void slicing() {
        Slice<ChecklistRun> first = repository.findForAdmin(FROM, TO, null, null, false, null, PageRequest.of(0, 2));
        Slice<ChecklistRun> third = repository.findForAdmin(FROM, TO, null, null, false, null, PageRequest.of(2, 2));

        assertEquals(2, first.getNumberOfElements());
        assertEquals(true, first.hasNext());
        assertEquals(0, third.getNumberOfElements());
        assertEquals(false, third.hasNext());
    }

    // ------------------------------------------------------------------ SQL

    private static List<Long> ids(Slice<ChecklistRun> slice) {
        return slice.getContent().stream().map(ChecklistRun::getRunId).toList();
    }

    private void user(String userId) {
        jdbc.sql("""
                INSERT INTO users (user_id, created_at, language_code, name, password,
                                   remaining_leave_days, role, ship_yard_area, tts_enabled)
                VALUES (:id, now(), 'ko', :id, 'x', 15, 'USER', 'A', false)
                """).param("id", userId).update();
    }

    private long equipment(String name, String nfc, String qr) {
        return jdbc.sql("INSERT INTO equipment (name, nfc_tag, qr_code) VALUES (:n, :nfc, :qr) RETURNING equipment_id")
                .param("n", name).param("nfc", nfc).param("qr", qr)
                .query(Long.class).single();
    }

    private int checklist(long equipmentId, String phase) {
        return jdbc.sql("INSERT INTO checklists (phase, title, equipment_id) VALUES (:phase, 't', :eq) RETURNING checklist_id")
                .param("phase", phase).param("eq", equipmentId)
                .query(Integer.class).single();
    }

    private long run(int checklistId, String userId, LocalDateTime startedAt, LocalDateTime closedAt, String outcome) {
        return jdbc.sql("""
                INSERT INTO checklist_runs (checklist_id, user_id, started_at, closed_at, outcome)
                VALUES (:cl, :user, :started, :closed, :outcome) RETURNING run_id
                """)
                .param("cl", checklistId).param("user", userId).param("started", startedAt)
                .param("closed", closedAt).param("outcome", outcome)
                .query(Long.class).single();
    }
}
