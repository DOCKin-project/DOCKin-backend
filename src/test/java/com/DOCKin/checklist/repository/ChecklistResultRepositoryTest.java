package com.DOCKin.checklist.repository;

import com.DOCKin.checklist.model.ChecklistResult;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 검증: {@code findLatestResultsByRunId}의 JPQL이 "회차 안에서 항목별 최신 한 행"을 준다.
 *
 * <p>{@code ChecklistStatusServiceTest}는 이 리포지토리를 목킹하므로 쿼리가 실행되지 않는다.
 * 네이티브 SQL에서 JPQL로 옮기면서 뜻이 바뀌지 않았는지는 실제 DB에 물어야 안다 —
 * 특히 <b>같은 항목의 여러 행 중 {@code MAX(result_id)} 하나만</b> 오는지,
 * <b>다른 회차의 결과가 섞이지 않는지</b> — 회차가 없던 때는 템플릿 전역이라 남의 체크가 섞였다(ADR-0011).
 *
 * <p>데이터는 SQL로 넣는다. {@code Member}·{@code Equipment} 엔티티까지 빌더로 세우는 것보다
 * 필요한 컬럼만 채우는 편이 이 테스트가 보려는 것에 가깝다.
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
class ChecklistResultRepositoryTest extends ContainerTestSupport {

    @Autowired
    private ChecklistResultRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private int checklistA;
    private int checklistB;
    private int itemA1;
    private int itemA2;
    private int itemB1;
    private long runA;
    private long runA2;
    private long runB;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT INTO users (user_id, created_at, language_code, name, password,
                                   remaining_leave_days, role, ship_yard_area, tts_enabled)
                VALUES ('u1', now(), 'ko', 'u1', 'x', 15, 'USER', 'A', false)
                """).update();
        long equipment = jdbc.sql("INSERT INTO equipment (name, nfc_tag, qr_code) VALUES ('e', 'nfc', 'qr') RETURNING equipment_id")
                .query(Long.class).single();
        checklistA = checklist(equipment, "PRE");
        checklistB = checklist(equipment, "POST");
        itemA1 = item(checklistA, 1);
        itemA2 = item(checklistA, 2);
        itemB1 = item(checklistB, 1);
        runA = run(checklistA, "u1", "2026-01-01 00:00:00");
        runA2 = run(checklistA, "u1", "2026-01-02 00:00:00"); // 같은 사람·같은 템플릿의 다음 날 회차
        runB = run(checklistB, "u1", "2026-01-01 00:00:00");
    }

    @Test
    @DisplayName("항목마다 result_id가 가장 큰 행 하나만 온다 - 체크→해제→체크면 마지막 체크")
    void 항목별_최신_한_행() {
        result(runA, itemA1, true);
        result(runA, itemA1, false);
        int latestA1 = result(runA, itemA1, true);
        int onlyA2 = result(runA, itemA2, false);

        List<ChecklistResult> found = repository.findLatestResultsByRunId(runA);

        Map<Integer, ChecklistResult> byItem = found.stream()
                .collect(Collectors.toMap(r -> r.getChecklistItem().getItemId(), Function.identity()));
        assertEquals(2, found.size(), "항목이 둘이면 행도 둘이어야 한다 - 이력 전부가 오면 N+1을 피한 뜻이 없다");
        assertEquals(latestA1, byItem.get(itemA1).getResultId());
        assertTrue(byItem.get(itemA1).getIsChecked());
        assertEquals(onlyA2, byItem.get(itemA2).getResultId());
        assertFalse(byItem.get(itemA2).getIsChecked());
    }

    @Test
    @DisplayName("다른 회차의 결과는 섞이지 않고, 이력이 없는 항목은 행이 없다")
    void 회차_경계() {
        result(runB, itemB1, true);

        assertTrue(repository.findLatestResultsByRunId(runA).isEmpty(),
                "A 회차에는 이력이 없는데 B 회차의 결과가 왔다");
        assertEquals(1, repository.findLatestResultsByRunId(runB).size());
    }

    @Test
    @DisplayName("같은 사람·같은 템플릿이라도 회차가 다르면 서로 안 보인다 — 어제 체크가 오늘 회차에 남지 않는다")
    void 같은_템플릿_다른_회차() {
        result(runA, itemA1, true);
        result(runA, itemA2, true);
        int today = result(runA2, itemA1, false);

        List<ChecklistResult> found = repository.findLatestResultsByRunId(runA2);

        assertEquals(1, found.size(), "오늘 회차에는 오늘 남긴 한 행만 있어야 한다");
        assertEquals(today, found.get(0).getResultId());
        assertFalse(found.get(0).getIsChecked());
    }

    // ------------------------------------------------------------------ SQL

    private int checklist(long equipmentId, String phase) {
        return jdbc.sql("INSERT INTO checklists (phase, title, equipment_id) VALUES (:phase, 't', :eq) RETURNING checklist_id")
                .param("phase", phase).param("eq", equipmentId)
                .query(Integer.class).single();
    }

    private int item(int checklistId, int sequence) {
        return jdbc.sql("INSERT INTO checklist_items (content, sequence, checklist_id) VALUES ('c', :seq, :cl) RETURNING item_id")
                .param("seq", sequence).param("cl", checklistId)
                .query(Integer.class).single();
    }

    /** 닫힌 회차로 넣는다 — 열린 회차는 (checklist, user)당 하나뿐이라 셋을 만들 수 없다. */
    private long run(int checklistId, String userId, String startedAt) {
        return jdbc.sql("""
                INSERT INTO checklist_runs (checklist_id, user_id, started_at, closed_at, outcome)
                VALUES (:cl, :user, CAST(:at AS timestamp), CAST(:at AS timestamp), 'COMPLETED') RETURNING run_id
                """)
                .param("cl", checklistId).param("user", userId).param("at", startedAt)
                .query(Long.class).single();
    }

    /** checked_at은 전부 같은 시각으로 둔다 - 최신 판정이 시각이 아니라 PK여야 한다는 것을 그대로 시험한다. */
    private int result(long runId, int itemId, boolean checked) {
        return jdbc.sql("""
                INSERT INTO checklist_results (run_id, checked_at, is_checked, checklist_item_id, user_id)
                VALUES (:run, TIMESTAMP '2026-01-01 00:00:00', :checked, :item, 'u1') RETURNING result_id
                """)
                .param("run", runId).param("checked", checked).param("item", itemId)
                .query(Integer.class).single();
    }
}
