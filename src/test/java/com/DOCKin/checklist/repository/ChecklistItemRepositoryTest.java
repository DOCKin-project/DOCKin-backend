package com.DOCKin.checklist.repository;

import com.DOCKin.checklist.model.ChecklistItem;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 검증: {@code findActiveAt} — 퇴역 시각과 회차 시작 시각의 경계 (ADR-0011 5절).
 * "퇴역 전에 연 회차는 그 항목을 계속 본다, 퇴역 뒤에 연 회차는 안 본다"가 실제 SQL에서 그렇게 풀리는지.
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
class ChecklistItemRepositoryTest extends ContainerTestSupport {

    private static final LocalDateTime RETIRED_AT = LocalDateTime.of(2026, 7, 10, 12, 0);

    @Autowired
    private ChecklistItemRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private int checklistId;
    private int alive;
    private int retired;

    @BeforeEach
    void seed() {
        long equipment = jdbc.sql("INSERT INTO equipment (name, nfc_tag, qr_code) VALUES ('e', 'nfc', 'qr') RETURNING equipment_id")
                .query(Long.class).single();
        checklistId = jdbc.sql("INSERT INTO checklists (phase, title, equipment_id) VALUES ('PRE', 't', :eq) RETURNING checklist_id")
                .param("eq", equipment).query(Integer.class).single();
        // 순서를 거꾸로 넣어 정렬이 sequence인지 본다
        retired = item(2, RETIRED_AT);
        alive = item(1, null);
    }

    @Test
    @DisplayName("퇴역 전에 연 회차는 퇴역한 항목을 그대로 본다 — 순서는 sequence")
    void beforeRetirement_seesBoth() {
        List<ChecklistItem> items = repository.findActiveAt(checklistId, RETIRED_AT.minusHours(1));

        assertEquals(List.of(alive, retired), items.stream().map(ChecklistItem::getItemId).toList());
    }

    @Test
    @DisplayName("퇴역 시각 정각과 그 뒤에 연 회차는 퇴역한 항목을 보지 않는다")
    void atAndAfterRetirement_seesAliveOnly() {
        assertEquals(List.of(alive), ids(repository.findActiveAt(checklistId, RETIRED_AT)));
        assertEquals(List.of(alive), ids(repository.findActiveAt(checklistId, RETIRED_AT.plusDays(30))));
    }

    private static List<Integer> ids(List<ChecklistItem> items) {
        return items.stream().map(ChecklistItem::getItemId).toList();
    }

    private int item(int sequence, LocalDateTime retiredAt) {
        return jdbc.sql("""
                INSERT INTO checklist_items (content, sequence, checklist_id, retired_at)
                VALUES ('c', :seq, :cl, :retired) RETURNING item_id
                """)
                .param("seq", sequence).param("cl", checklistId).param("retired", retiredAt)
                .query(Integer.class).single();
    }
}
