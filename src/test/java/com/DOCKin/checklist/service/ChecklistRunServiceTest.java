package com.DOCKin.checklist.service;

import com.DOCKin.checklist.dto.ChecklistDetailResponseDto;
import com.DOCKin.checklist.dto.ChecklistResultResponseDto;
import com.DOCKin.checklist.dto.ChecklistRunResponseDto;
import com.DOCKin.checklist.model.Checklist;
import com.DOCKin.checklist.model.ChecklistItem;
import com.DOCKin.checklist.model.ChecklistPhase;
import com.DOCKin.checklist.model.ChecklistResult;
import com.DOCKin.checklist.model.ChecklistRun;
import com.DOCKin.checklist.repository.ChecklistItemRepository;
import com.DOCKin.checklist.repository.ChecklistRepository;
import com.DOCKin.checklist.repository.ChecklistResultRepository;
import com.DOCKin.checklist.repository.ChecklistRunRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.repository.MemberRepository;
import com.DOCKin.worklog.model.Equipment;
import com.DOCKin.worklog.repository.EquipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 점검 회차(ADR-0011)의 규칙. 리포지토리는 목이고 {@code TransactionTemplate}은 콜백을 그 자리에서 실행한다 —
 * 트랜잭션 경계가 아니라 <b>어느 회차에 무엇이 기록되는가</b>를 본다. 쿼리의 뜻은 {@code ChecklistResultRepositoryTest}가 실제 DB로.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChecklistRunServiceTest {

    @Mock private ChecklistRepository checklistRepository;
    @Mock private ChecklistItemRepository checklistItemRepository;
    @Mock private ChecklistResultRepository checklistResultRepository;
    @Mock private ChecklistRunRepository checklistRunRepository;
    @Mock private EquipmentRepository equipmentRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private TransactionTemplate transactionTemplate;

    private static final String USER = "worker01";
    private static final String OTHER = "worker02";
    private static final long EQUIPMENT = 7L;
    private static final int CHECKLIST = 10;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 10, 8, 0);

    private ChecklistRunService service;
    private Checklist checklist;
    private ChecklistItem item1;
    private ChecklistItem item2;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        service = new ChecklistRunService(checklistRepository, checklistItemRepository, checklistResultRepository,
                checklistRunRepository, equipmentRepository, memberRepository, transactionTemplate, clock);

        // 트랜잭션 템플릿은 콜백을 바로 실행한다. 경계는 여기서 보는 것이 아니다.
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));

        Equipment equipment = Equipment.builder().equipmentId(EQUIPMENT).name("크레인").qrCode("qr").nfcTag("nfc").build();
        checklist = Checklist.builder().checklistId(CHECKLIST).equipment(equipment).title("작업 전").phase(ChecklistPhase.PRE).build();
        item1 = ChecklistItem.builder().itemId(1).checklist(checklist).content("와이어 마모").sequence(1).build();
        item2 = ChecklistItem.builder().itemId(2).checklist(checklist).content("브레이크").sequence(2).build();

        when(equipmentRepository.findById(EQUIPMENT)).thenReturn(Optional.of(equipment));
        when(checklistRepository.findByEquipment_EquipmentIdAndPhase(EQUIPMENT, ChecklistPhase.PRE)).thenReturn(Optional.of(checklist));
        when(checklistRepository.findById(CHECKLIST)).thenReturn(Optional.of(checklist));
        when(checklistItemRepository.findActiveAt(eq(CHECKLIST), any())).thenReturn(List.of(item1, item2));
        when(checklistItemRepository.findById(1)).thenReturn(Optional.of(item1));
        when(checklistItemRepository.findById(2)).thenReturn(Optional.of(item2));
        when(memberRepository.findByUserId(USER)).thenReturn(Optional.of(member(USER)));
        when(checklistRunRepository.saveAndFlush(any(ChecklistRun.class))).thenAnswer(inv -> {
            ChecklistRun run = inv.getArgument(0);
            if (run.getRunId() == null) ReflectionTestUtils.setField(run, "runId", 100L);
            return run;
        });
        when(checklistResultRepository.save(any(ChecklistResult.class))).thenAnswer(inv -> {
            ChecklistResult r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "resultId", 500);
            ReflectionTestUtils.setField(r, "checkedAt", NOW);
            return r;
        });
        when(checklistResultRepository.findLatestResultsByRunId(any())).thenReturn(List.of());
    }

    // ── 열기 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("열린 회차가 없으면 새로 연다 — 201, 전 항목 미체크, 시작 시각은 시계")
    void open_noOpenRun_creates() {
        noOpenRun();

        ChecklistRunService.Opened opened = service.open(USER, EQUIPMENT, ChecklistPhase.PRE);

        assertTrue(opened.created());
        assertEquals("IN_PROGRESS", opened.run().getStatus());
        assertEquals(NOW, opened.run().getStartedAt());
        assertEquals(2, opened.run().getItems().size());
        assertTrue(opened.run().getItems().stream().noneMatch(i -> i.isChecked()));
    }

    @Test
    @DisplayName("12시간 안의 열린 회차가 있으면 그것을 돌려준다 — 200, 새로 만들지 않는다 (더블탭·재접속)")
    void open_recentOpenRun_returnsExisting() {
        ChecklistRun existing = openRun(42L, NOW.minusHours(2));
        when(checklistRunRepository.findFirstByChecklist_ChecklistIdAndMember_UserIdAndClosedAtIsNull(CHECKLIST, USER))
                .thenReturn(Optional.of(existing));

        ChecklistRunService.Opened opened = service.open(USER, EQUIPMENT, ChecklistPhase.PRE);

        assertFalse(opened.created());
        assertEquals(42L, opened.run().getRunId());
        verify(checklistRunRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("12시간 지난 열린 회차는 ABANDONED로 닫고 새로 연다 — 어제의 체크가 오늘의 점검이 되지 않는다")
    void open_staleOpenRun_abandonsAndCreates() {
        ChecklistRun stale = openRun(42L, NOW.minusHours(13));
        when(checklistRunRepository.findFirstByChecklist_ChecklistIdAndMember_UserIdAndClosedAtIsNull(CHECKLIST, USER))
                .thenReturn(Optional.of(stale));

        ChecklistRunService.Opened opened = service.open(USER, EQUIPMENT, ChecklistPhase.PRE);

        assertTrue(opened.created());
        assertEquals(100L, opened.run().getRunId());
        assertEquals("ABANDONED", stale.status());
        assertEquals(NOW, stale.getClosedAt());
    }

    @Test
    @DisplayName("동시에 두 번 열면 진 쪽은 유니크 위반을 밖에서 잡아 이긴 쪽의 회차를 돌려준다")
    void open_race_loserReturnsWinnersRun() {
        ChecklistRun winner = openRun(77L, NOW);
        when(checklistRunRepository.findFirstByChecklist_ChecklistIdAndMember_UserIdAndClosedAtIsNull(CHECKLIST, USER))
                .thenReturn(Optional.empty())   // 열 때는 없었고
                .thenReturn(Optional.of(winner)); // 실패 뒤 다시 보니 있다
        when(checklistRunRepository.saveAndFlush(any(ChecklistRun.class)))
                .thenThrow(new DataIntegrityViolationException("uq_checklist_runs_open"));

        ChecklistRunService.Opened opened = service.open(USER, EQUIPMENT, ChecklistPhase.PRE);

        assertFalse(opened.created());
        assertEquals(77L, opened.run().getRunId());
    }

    // ── 조회·권한 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("남의 회차는 403이고, 관리자는 본다")
    void get_ownerOrAdmin() {
        ChecklistRun run = openRun(42L, NOW);
        when(checklistRunRepository.findById(42L)).thenReturn(Optional.of(run));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.get(42L, OTHER, false));
        assertEquals(ErrorCode.ACCESS_DENIED, ex.getErrorCode());

        assertEquals(42L, service.get(42L, OTHER, true).getRunId());
        assertEquals(42L, service.get(42L, USER, false).getRunId());
    }

    // ── 체크 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("체크는 회차와 회차의 점검자로 기록된다")
    void check_appendsResultToRun() {
        ChecklistRun run = openRun(42L, NOW);
        when(checklistRunRepository.findById(42L)).thenReturn(Optional.of(run));

        ChecklistResultResponseDto result = service.check(42L, 1, USER, true);

        assertEquals(42L, result.getRunId());
        assertEquals(USER, result.getUserId());
        assertTrue(result.getIsChecked());
    }

    @Test
    @DisplayName("닫힌 회차에는 체크할 수 없다 — 409 CHECKLIST_RUN_CLOSED")
    void check_closedRun_rejected() {
        ChecklistRun run = openRun(42L, NOW.minusHours(1));
        run.complete(NOW);
        when(checklistRunRepository.findById(42L)).thenReturn(Optional.of(run));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.check(42L, 1, USER, true));

        assertEquals(ErrorCode.CHECKLIST_RUN_CLOSED, ex.getErrorCode());
        verify(checklistResultRepository, never()).save(any());
    }

    @Test
    @DisplayName("남의 회차 체크는 존재 확인보다 먼저 403이고, 다른 템플릿의 항목은 CHECKLIST_ITEM_MISMATCH")
    void check_forbiddenAndMismatch() {
        ChecklistRun run = openRun(42L, NOW);
        when(checklistRunRepository.findById(42L)).thenReturn(Optional.of(run));
        Checklist other = Checklist.builder().checklistId(99).phase(ChecklistPhase.POST).build();
        when(checklistItemRepository.findById(9)).thenReturn(Optional.of(
                ChecklistItem.builder().itemId(9).checklist(other).content("x").sequence(1).build()));

        assertEquals(ErrorCode.ACCESS_DENIED,
                assertThrows(BusinessException.class, () -> service.check(42L, 1, OTHER, true)).getErrorCode());
        assertEquals(ErrorCode.CHECKLIST_ITEM_MISMATCH,
                assertThrows(BusinessException.class, () -> service.check(42L, 9, USER, true)).getErrorCode());
    }

    @Test
    @DisplayName("회차가 열리기 전에 퇴역한 항목은 체크할 수 없다(CHECKLIST_ITEM_RETIRED); 회차 도중 퇴역한 항목은 된다")
    void check_retiredItem_dependsOnRunStart() {
        ChecklistRun run = openRun(42L, NOW.minusHours(1));
        when(checklistRunRepository.findById(42L)).thenReturn(Optional.of(run));
        ChecklistItem retiredBefore = ChecklistItem.builder().itemId(3).checklist(checklist).content("옛 항목").sequence(3)
                .retiredAt(NOW.minusHours(2)).build();
        ChecklistItem retiredDuring = ChecklistItem.builder().itemId(4).checklist(checklist).content("방금 퇴역").sequence(4)
                .retiredAt(NOW.minusMinutes(10)).build();
        when(checklistItemRepository.findById(3)).thenReturn(Optional.of(retiredBefore));
        when(checklistItemRepository.findById(4)).thenReturn(Optional.of(retiredDuring));

        assertEquals(ErrorCode.CHECKLIST_ITEM_RETIRED,
                assertThrows(BusinessException.class, () -> service.check(42L, 3, USER, true)).getErrorCode());
        assertEquals(4, service.check(42L, 4, USER, true).getItemId());
    }

    @Test
    @DisplayName("회차의 항목 목록은 회차가 열린 시점 기준이다 — findActiveAt(checklist, startedAt)")
    void run_itemsAreActiveAtStart() {
        ChecklistRun run = openRun(42L, NOW.minusHours(3));
        when(checklistRunRepository.findById(42L)).thenReturn(Optional.of(run));

        service.get(42L, USER, false);

        verify(checklistItemRepository).findActiveAt(CHECKLIST, NOW.minusHours(3));
    }

    // ── 완료 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("미체크 항목이 하나라도 있으면 완료할 수 없다 — 409 CHECKLIST_RUN_INCOMPLETE, 회차는 열린 채")
    void complete_unchecked_rejected() {
        ChecklistRun run = openRun(42L, NOW);
        when(checklistRunRepository.findById(42L)).thenReturn(Optional.of(run));
        when(checklistResultRepository.findLatestResultsByRunId(42L))
                .thenReturn(List.of(result(run, item1, true))); // item2는 기록 없음

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(42L, USER));

        assertEquals(ErrorCode.CHECKLIST_RUN_INCOMPLETE, ex.getErrorCode());
        assertTrue(run.isOpen());
    }

    @Test
    @DisplayName("해제된 항목도 미체크다 — 마지막 기록이 false면 완료 불가")
    void complete_lastResultUnchecked_rejected() {
        ChecklistRun run = openRun(42L, NOW);
        when(checklistRunRepository.findById(42L)).thenReturn(Optional.of(run));
        when(checklistResultRepository.findLatestResultsByRunId(42L))
                .thenReturn(List.of(result(run, item1, true), result(run, item2, false)));

        assertEquals(ErrorCode.CHECKLIST_RUN_INCOMPLETE,
                assertThrows(BusinessException.class, () -> service.complete(42L, USER)).getErrorCode());
    }

    @Test
    @DisplayName("전 항목 체크면 COMPLETED로 닫힌다")
    void complete_allChecked_closes() {
        ChecklistRun run = openRun(42L, NOW.minusMinutes(5));
        when(checklistRunRepository.findById(42L)).thenReturn(Optional.of(run));
        when(checklistResultRepository.findLatestResultsByRunId(42L))
                .thenReturn(List.of(result(run, item1, true), result(run, item2, true)));

        ChecklistRunResponseDto done = service.complete(42L, USER);

        assertEquals("COMPLETED", done.getStatus());
        assertEquals(NOW, done.getClosedAt());
        assertTrue(done.getItems().stream().allMatch(i -> i.isChecked()));
    }

    // ── 옛 경로 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("옛 조회: 열린 회차가 없으면 전부 미체크, myOpenRunId는 null — 남의 최신 결과를 보여 주지 않는다. 항목은 지금 살아 있는 것")
    void template_noOpenRun_allUnchecked() {
        noOpenRun();

        ChecklistDetailResponseDto dto = service.templateWithMyOpenRun(USER, EQUIPMENT, ChecklistPhase.PRE);

        verify(checklistItemRepository).findActiveAt(CHECKLIST, NOW);
        assertNull(dto.getMyOpenRunId());
        assertEquals(2, dto.getItems().size());
        assertTrue(dto.getItems().stream().noneMatch(i -> i.isChecked()));
        verify(checklistResultRepository, never()).findLatestResultsByRunId(any());
    }

    @Test
    @DisplayName("옛 조회: 내 열린 회차가 있으면 그 회차의 상태다")
    void template_withOpenRun_reflectsRun() {
        ChecklistRun run = openRun(42L, NOW.minusHours(1));
        when(checklistRunRepository.findFirstByChecklist_ChecklistIdAndMember_UserIdAndClosedAtIsNull(CHECKLIST, USER))
                .thenReturn(Optional.of(run));
        when(checklistResultRepository.findLatestResultsByRunId(42L)).thenReturn(List.of(result(run, item1, true)));

        ChecklistDetailResponseDto dto = service.templateWithMyOpenRun(USER, EQUIPMENT, ChecklistPhase.PRE);

        assertEquals(42L, dto.getMyOpenRunId());
        assertTrue(dto.getItems().get(0).isChecked());
        assertFalse(dto.getItems().get(1).isChecked());
    }

    @Test
    @DisplayName("옛 체크: 열린 회차가 없으면 열어서 거기 기록한다")
    void checkViaTemplate_opensThenChecks() {
        noOpenRun();
        when(checklistRunRepository.findById(100L)).thenAnswer(inv -> Optional.of(openRunWithId(100L, NOW)));

        ChecklistResultResponseDto result = service.checkViaTemplate(USER, CHECKLIST, 1, true);

        assertEquals(100L, result.getRunId());
        verify(checklistRunRepository).saveAndFlush(any(ChecklistRun.class));
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private void noOpenRun() {
        when(checklistRunRepository.findFirstByChecklist_ChecklistIdAndMember_UserIdAndClosedAtIsNull(CHECKLIST, USER))
                .thenReturn(Optional.empty());
    }

    private ChecklistRun openRun(long runId, LocalDateTime startedAt) {
        return openRunWithId(runId, startedAt);
    }

    private ChecklistRun openRunWithId(long runId, LocalDateTime startedAt) {
        ChecklistRun run = ChecklistRun.builder().checklist(checklist).member(member(USER)).startedAt(startedAt).build();
        ReflectionTestUtils.setField(run, "runId", runId);
        return run;
    }

    private ChecklistResult result(ChecklistRun run, ChecklistItem item, boolean checked) {
        return ChecklistResult.builder().run(run).checklistItem(item).member(member(USER)).isChecked(checked).checkedAt(NOW).build();
    }

    private static Member member(String userId) {
        return Member.builder().userId(userId).build();
    }
}
