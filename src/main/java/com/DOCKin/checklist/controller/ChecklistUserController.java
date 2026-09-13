package com.DOCKin.checklist.controller;

import com.DOCKin.checklist.dto.ChecklistCheckRequestDto;
import com.DOCKin.checklist.dto.ChecklistDetailResponseDto;
import com.DOCKin.checklist.dto.ChecklistResultResponseDto;
import com.DOCKin.checklist.model.ChecklistPhase;
import com.DOCKin.checklist.service.ChecklistStatusService;
import com.DOCKin.global.security.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "사용자 체크리스트", description = "장비 작업 전/후 점검 체크리스트 조회/체크 담당 api")
@Slf4j
@RestController
@RequestMapping("/api/checklist/user")
@RequiredArgsConstructor
public class ChecklistUserController {

    private final ChecklistStatusService checklistStatusService;

    @Operation(summary = "체크리스트 점검 현황 조회", description = "장비/점검 단계로 체크리스트와 항목별 최신 점검 상태를 조회함")
    @GetMapping("/checklists")
    public ResponseEntity<ChecklistDetailResponseDto> getChecklistStatus(
            @RequestParam Long equipmentId,
            @RequestParam ChecklistPhase phase) {
        return ResponseEntity.ok(checklistStatusService.getChecklistStatus(equipmentId, phase));
    }

    @Operation(summary = "체크리스트 항목 체크/해제", description = "항목을 체크/해제함 (매번 새 점검 기록이 남는 append-only 방식)")
    @PatchMapping("/checklists/{checklistId}/items/{itemId}/check")
    public ResponseEntity<ChecklistResultResponseDto> checkItem(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Integer checklistId,
            @PathVariable Integer itemId,
            @Valid @RequestBody ChecklistCheckRequestDto dto) {
        String userId = customUserDetails.getMember().getUserId();
        ChecklistResultResponseDto response = checklistStatusService.checkItem(
                userId, checklistId, itemId, dto.getIsChecked());
        return ResponseEntity.ok(response);
    }
}
