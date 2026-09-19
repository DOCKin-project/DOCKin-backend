package com.DOCKin.checklist.controller;

import com.DOCKin.checklist.dto.ChecklistCheckRequestDto;
import com.DOCKin.checklist.dto.ChecklistDetailResponseDto;
import com.DOCKin.checklist.dto.ChecklistResultResponseDto;
import com.DOCKin.checklist.dto.ChecklistRunOpenRequestDto;
import com.DOCKin.checklist.dto.ChecklistRunResponseDto;
import com.DOCKin.checklist.model.ChecklistPhase;
import com.DOCKin.checklist.service.ChecklistRunService;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.member.model.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 근무자 체크리스트 — 점검 <b>회차</b> 단위다(ADR-0011).
 *
 * <p>흐름: QR/NFC → {@code POST /runs}(열기, 멱등) → {@code PATCH /runs/{id}/items/{itemId}}(체크) → {@code PATCH /runs/{id}/complete}.
 * 아래 두 옛 경로({@code GET /checklists}, {@code PATCH /checklists/.../check})는 앱이 옮길 때까지의 별칭이다 —
 * 의미는 이미 회차 단위로 바뀌었다(내 열린 회차에 기록하고, 없으면 연다).
 */
@Tag(name = "사용자 체크리스트", description = "장비 작업 전/후 점검 — 회차를 열고, 항목을 체크하고, 완료한다")
@Slf4j
@RestController
@RequestMapping("/api/checklist/user")
@RequiredArgsConstructor
public class ChecklistUserController {

    /** 내 회차 목록의 기본·최대 기간. 근태 조회(P2-20-6)와 같은 값이다. */
    static final int DEFAULT_RANGE_DAYS = 31;
    static final int MAX_RANGE_DAYS = 366;

    private final ChecklistRunService checklistRunService;
    private final Clock clock;

    @Operation(summary = "점검 회차 열기 (QR/NFC 직후)",
            description = "내 열린 회차가 12시간 안에 있으면 그것을 200으로, 없으면 새로 열어 201로. 12시간 지난 열린 회차는 ABANDONED로 닫고 새로 연다")
    @PostMapping("/runs")
    public ResponseEntity<ChecklistRunResponseDto> openRun(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @Valid @RequestBody ChecklistRunOpenRequestDto dto) {
        String userId = customUserDetails.getMember().getUserId();
        ChecklistRunService.Opened opened = checklistRunService.open(userId, dto.getEquipmentId(), dto.getPhase());
        return ResponseEntity.status(opened.created() ? HttpStatus.CREATED : HttpStatus.OK).body(opened.run());
    }

    @Operation(summary = "점검 회차 조회", description = "회차와 그 안의 항목별 최신 상태. 본인 또는 관리자")
    @GetMapping("/runs/{runId}")
    public ResponseEntity<ChecklistRunResponseDto> getRun(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Long runId) {
        String userId = customUserDetails.getMember().getUserId();
        boolean admin = customUserDetails.getMember().getRole() == UserRole.ADMIN;
        return ResponseEntity.ok(checklistRunService.get(runId, userId, admin));
    }

    @Operation(summary = "내 점검 회차 목록",
            description = "from~to(yyyy-MM-dd, 둘 다 포함) 시작 시각 기준 최신순. to 생략은 오늘, from 생략은 to의 31일 전. 366일 초과·역순은 400. 항목은 싣지 않는다")
    @GetMapping("/runs")
    public ResponseEntity<Slice<ChecklistRunResponseDto>> myRuns(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1);
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.ATTENDANCE_RANGE_TOO_LONG);
        }
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(checklistRunService.myRuns(userId, start, end, pageable));
    }

    @Operation(summary = "항목 체크/해제", description = "열린 회차에 점검 기록 한 행을 남긴다(append-only). 닫힌 회차는 409, 남의 회차는 403")
    @PatchMapping("/runs/{runId}/items/{itemId}")
    public ResponseEntity<ChecklistResultResponseDto> checkItem(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Long runId,
            @PathVariable Integer itemId,
            @Valid @RequestBody ChecklistCheckRequestDto dto) {
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(checklistRunService.check(runId, itemId, userId, dto.getIsChecked()));
    }

    @Operation(summary = "점검 완료", description = "전 항목이 체크돼 있어야 한다. 미체크가 있으면 409 CHECKLIST_RUN_INCOMPLETE — 무엇이 비었는지는 회차 조회로")
    @PatchMapping("/runs/{runId}/complete")
    public ResponseEntity<ChecklistRunResponseDto> complete(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Long runId) {
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(checklistRunService.complete(runId, userId));
    }

    // ------------------------------------------------------------------ 옛 경로 (앱이 /runs로 옮기면 삭제)

    @Operation(summary = "[옛 경로] 체크리스트 점검 현황 조회",
            description = "템플릿 + 내 열린 회차의 상태(myOpenRunId). 열린 회차가 없으면 전부 미체크 — 남의 체크는 더 이상 보이지 않는다. POST /runs로 옮길 것")
    @GetMapping("/checklists")
    public ResponseEntity<ChecklistDetailResponseDto> getChecklistStatus(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam Long equipmentId,
            @RequestParam ChecklistPhase phase) {
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(checklistRunService.templateWithMyOpenRun(userId, equipmentId, phase));
    }

    @Operation(summary = "[옛 경로] 체크리스트 항목 체크/해제",
            description = "내 열린 회차에 기록하고, 없으면 연다. PATCH /runs/{runId}/items/{itemId}로 옮길 것")
    @PatchMapping("/checklists/{checklistId}/items/{itemId}/check")
    public ResponseEntity<ChecklistResultResponseDto> checkItemViaTemplate(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Integer checklistId,
            @PathVariable Integer itemId,
            @Valid @RequestBody ChecklistCheckRequestDto dto) {
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(checklistRunService.checkViaTemplate(userId, checklistId, itemId, dto.getIsChecked()));
    }
}
