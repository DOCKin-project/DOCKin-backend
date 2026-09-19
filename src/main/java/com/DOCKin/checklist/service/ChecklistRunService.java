package com.DOCKin.checklist.service;

import com.DOCKin.checklist.dto.ChecklistDetailResponseDto;
import com.DOCKin.checklist.dto.ChecklistItemStatusResponseDto;
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
import com.DOCKin.worklog.repository.EquipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 점검 회차 (ADR-0011). 근무자가 QR/NFC를 찍고 → 회차를 열고 → 항목을 체크하고 → 완료한다.
 *
 * <p>{@code ChecklistStatusService}를 대체한다. 그 서비스는 "현재 상태"를 템플릿 전역의 최신 결과로 봤다 —
 * A가 월요일에 체크한 것이 화요일 B에게 체크된 채로 보였다. 여기서는 모든 상태가 <b>회차 안</b>이다.
 *
 * <p>쿼리 수는 그대로다. 회차 조회 = 회차 1 + 항목 1 + 회차 내 최신 결과 1.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChecklistRunService {

    private final ChecklistRepository checklistRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final ChecklistResultRepository checklistResultRepository;
    private final ChecklistRunRepository checklistRunRepository;
    private final EquipmentRepository equipmentRepository;
    private final MemberRepository memberRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 열기의 결과 — 새로 열었는지(201)와 기존 회차를 돌려줬는지(200)를 컨트롤러가 가른다. */
    public record Opened(ChecklistRunResponseDto run, boolean created) {}

    /**
     * 회차 열기. <b>멱등이다</b> — 내 열린 회차가 있고 12시간 안이면 그것을 돌려준다. 12시간이 지났으면 ABANDONED로
     * 닫고 새로 연다. 더블탭·재접속·앱 재시작이 전부 같은 회차로 돌아온다.
     *
     * <p>동시에 두 번 열면 부분 유니크 {@code uq_checklist_runs_open}에 한쪽이 걸린다. 그 트랜잭션은 이미 중단된 상태라
     * 안에서 복구할 수 없으므로 {@code ChatService.saveMessage}처럼 트랜잭션 <b>밖</b>에서 잡아 다시 찾는다 —
     * 그래서 {@code @Transactional}이 아니라 {@link TransactionTemplate}이다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Opened open(String userId, Long equipmentId, ChecklistPhase phase) {
        try {
            return transactionTemplate.execute(tx -> openTx(userId, equipmentId, phase));
        } catch (DataIntegrityViolationException e) {
            // 동시 열기의 진 쪽. 이긴 쪽의 회차가 이제는 보인다. 지연 로딩이 있어 읽기도 트랜잭션 안에서.
            return transactionTemplate.execute(tx -> {
                Checklist checklist = requireChecklist(equipmentId, phase);
                ChecklistRun run = checklistRunRepository
                        .findFirstByChecklist_ChecklistIdAndMember_UserIdAndClosedAtIsNull(checklist.getChecklistId(), userId)
                        .orElseThrow(() -> e);
                return new Opened(toDto(run), false);
            });
        }
    }

    private Opened openTx(String userId, Long equipmentId, ChecklistPhase phase) {
        Member member = requireMember(userId);
        Checklist checklist = requireChecklist(equipmentId, phase);
        LocalDateTime now = LocalDateTime.now(clock);

        Optional<ChecklistRun> open = checklistRunRepository
                .findFirstByChecklist_ChecklistIdAndMember_UserIdAndClosedAtIsNull(checklist.getChecklistId(), userId);
        if (open.isPresent() && !open.get().isStale(now)) {
            return new Opened(toDto(open.get()), false);
        }
        open.ifPresent(stale -> {
            // 어제 놓고 간 회차. 이어서 하지 않는다 — 어제의 체크가 오늘의 점검이 되면 안 된다.
            stale.abandon(now);
            checklistRunRepository.saveAndFlush(stale); // 유니크를 비워야 아래 INSERT가 들어간다
            log.info("[점검] 12시간 넘은 열린 회차를 ABANDONED로 닫음 - runId={}, userId={}", stale.getRunId(), userId);
        });
        ChecklistRun run = checklistRunRepository.saveAndFlush(
                ChecklistRun.builder().checklist(checklist).member(member).startedAt(now).build());
        return new Opened(toDto(run), true);
    }

    /** 회차 조회 — 본인 또는 ADMIN. */
    public ChecklistRunResponseDto get(Long runId, String userId, boolean admin) {
        ChecklistRun run = requireRun(runId);
        if (!admin && !run.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return toDto(run);
    }

    /** 내 회차 목록 — 기간으로 (근태 조회와 같은 꼴, 기본은 컨트롤러가 정한다). */
    public Slice<ChecklistRunResponseDto> myRuns(String userId, LocalDate from, LocalDate to, Pageable pageable) {
        return checklistRunRepository
                .findByMember_UserIdAndStartedAtBetweenOrderByStartedAtDesc(
                        userId, from.atStartOfDay(), to.plusDays(1).atStartOfDay().minusNanos(1), pageable)
                .map(run -> ChecklistRunResponseDto.of(run, List.of()));
    }

    /** 항목 체크/해제 — 결과 한 행을 회차에 append. 열린 회차, 본인만. */
    @Transactional
    public ChecklistResultResponseDto check(Long runId, Integer itemId, String userId, boolean isChecked) {
        ChecklistRun run = requireOpenOwnRun(runId, userId);
        ChecklistItem item = checklistItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_ITEM_NOT_FOUND));
        if (!item.getChecklist().getChecklistId().equals(run.getChecklist().getChecklistId())) {
            throw new BusinessException(ErrorCode.CHECKLIST_ITEM_MISMATCH);
        }
        ChecklistResult result = ChecklistResult.builder()
                .run(run)
                .checklistItem(item)
                .member(run.getMember())
                .isChecked(isChecked)
                .build();
        return ChecklistResultResponseDto.fromEntity(checklistResultRepository.save(result));
    }

    /**
     * 완료. <b>전 항목이 체크돼 있어야 한다</b> — 안전 점검의 목적이 그것이다. 하나라도 미체크(또는 기록 없음)면
     * 409 {@code CHECKLIST_RUN_INCOMPLETE}. 무엇이 비었는지는 회차 조회가 보여 준다.
     */
    @Transactional
    public ChecklistRunResponseDto complete(Long runId, String userId) {
        ChecklistRun run = requireOpenOwnRun(runId, userId);
        List<ChecklistItem> items = itemsOf(run);
        Map<Integer, ChecklistResult> latest = latestOf(run);
        boolean allChecked = items.stream().allMatch(item -> {
            ChecklistResult r = latest.get(item.getItemId());
            return r != null && Boolean.TRUE.equals(r.getIsChecked());
        });
        if (!allChecked) {
            throw new BusinessException(ErrorCode.CHECKLIST_RUN_INCOMPLETE);
        }
        run.complete(LocalDateTime.now(clock));
        return ChecklistRunResponseDto.of(run, statusOf(items, latest));
    }

    // ------------------------------------------------------------------ 옛 엔드포인트 (앱이 옮길 때까지)

    /**
     * 템플릿 + 내 열린 회차의 상태. 예전 {@code GET /checklists?equipmentId&phase}의 자리.
     * 열린 회차가 없으면 전부 미체크다 — 예전처럼 남의 최신 결과를 보여 주지 않는다.
     */
    public ChecklistDetailResponseDto templateWithMyOpenRun(String userId, Long equipmentId, ChecklistPhase phase) {
        Checklist checklist = requireChecklist(equipmentId, phase);
        List<ChecklistItem> items = checklistItemRepository
                .findByChecklist_ChecklistIdOrderBySequenceAscItemIdAsc(checklist.getChecklistId());
        Optional<ChecklistRun> open = checklistRunRepository
                .findFirstByChecklist_ChecklistIdAndMember_UserIdAndClosedAtIsNull(checklist.getChecklistId(), userId)
                .filter(run -> !run.isStale(LocalDateTime.now(clock)));
        Map<Integer, ChecklistResult> latest = open.map(this::latestOf).orElse(Map.of());
        return ChecklistDetailResponseDto.of(checklist, open.map(ChecklistRun::getRunId).orElse(null),
                statusOf(items, latest));
    }

    /**
     * 예전 {@code PATCH /checklists/{id}/items/{itemId}/check}의 별칭 — 내 열린 회차에 기록하고, 없으면 연다.
     * 옛 앱이 그대로 동작하되 의미는 회차 단위가 된다. 앱이 {@code /runs}로 옮기면 지운다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ChecklistResultResponseDto checkViaTemplate(String userId, Integer checklistId, Integer itemId, boolean isChecked) {
        record Key(Long equipmentId, ChecklistPhase phase) {}
        Key key = transactionTemplate.execute(tx -> {
            Checklist checklist = checklistRepository.findById(checklistId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND));
            return new Key(checklist.getEquipment().getEquipmentId(), checklist.getPhase());
        });
        Opened opened = open(userId, key.equipmentId(), key.phase());
        return transactionTemplate.execute(tx -> check(opened.run().getRunId(), itemId, userId, isChecked));
    }

    // ------------------------------------------------------------------ 내부

    private ChecklistRunResponseDto toDto(ChecklistRun run) {
        return ChecklistRunResponseDto.of(run, statusOf(itemsOf(run), latestOf(run)));
    }

    private List<ChecklistItem> itemsOf(ChecklistRun run) {
        return checklistItemRepository
                .findByChecklist_ChecklistIdOrderBySequenceAscItemIdAsc(run.getChecklist().getChecklistId());
    }

    private Map<Integer, ChecklistResult> latestOf(ChecklistRun run) {
        return checklistResultRepository.findLatestResultsByRunId(run.getRunId()).stream()
                .collect(Collectors.toMap(r -> r.getChecklistItem().getItemId(), Function.identity()));
    }

    private static List<ChecklistItemStatusResponseDto> statusOf(List<ChecklistItem> items,
                                                                 Map<Integer, ChecklistResult> latest) {
        return items.stream()
                .map(item -> {
                    ChecklistResult r = latest.get(item.getItemId());
                    return ChecklistItemStatusResponseDto.builder()
                            .itemId(item.getItemId())
                            .content(item.getContent())
                            .sequence(item.getSequence())
                            .checked(r != null && Boolean.TRUE.equals(r.getIsChecked()))
                            .lastCheckedAt(r != null ? r.getCheckedAt() : null)
                            .lastCheckedBy(r != null ? r.getMember().getUserId() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private ChecklistRun requireRun(Long runId) {
        return checklistRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_RUN_NOT_FOUND));
    }

    /** 남의 회차는 존재 여부보다 먼저 거부한다 — 순서를 바꾸면 회차 번호를 훑어 존재 여부를 캘 수 있다. */
    private ChecklistRun requireOpenOwnRun(Long runId, String userId) {
        ChecklistRun run = requireRun(runId);
        if (!run.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (!run.isOpen()) {
            throw new BusinessException(ErrorCode.CHECKLIST_RUN_CLOSED);
        }
        return run;
    }

    private Member requireMember(String userId) {
        return memberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private Checklist requireChecklist(Long equipmentId, ChecklistPhase phase) {
        equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EQUIPMENT_NOT_FOUND));
        return checklistRepository.findByEquipment_EquipmentIdAndPhase(equipmentId, phase)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND));
    }
}
