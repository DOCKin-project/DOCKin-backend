package com.DOCKin.checklist.controller;

import com.DOCKin.checklist.dto.ChecklistCreateRequestDto;
import com.DOCKin.checklist.dto.ChecklistItemRequestDto;
import com.DOCKin.checklist.dto.ChecklistItemSimpleResponseDto;
import com.DOCKin.checklist.dto.ChecklistItemUpdateRequestDto;
import com.DOCKin.checklist.dto.ChecklistResponseDto;
import com.DOCKin.checklist.dto.ChecklistUpdateRequestDto;
import com.DOCKin.checklist.service.ChecklistService;
import com.DOCKin.global.security.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "관리자용 체크리스트 관리", description = "장비 작업 전/후 점검 체크리스트를 관리할 수 있는 api")
@Slf4j
@RestController
@RequestMapping("/api/checklist/admin")
@RequiredArgsConstructor
public class ChecklistAdminController {

    private final ChecklistService checklistService;

    @Operation(summary = "체크리스트 생성", description = "장비의 작업 전/후 점검 체크리스트를 항목과 함께 생성함")
    @PostMapping("/checklists")
    public ResponseEntity<ChecklistResponseDto> createChecklist(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @Valid @RequestBody ChecklistCreateRequestDto dto) {
        String userId = customUserDetails.getMember().getUserId();
        ChecklistResponseDto response = checklistService.createChecklist(userId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "체크리스트 상세 조회", description = "체크리스트와 항목 목록을 조회함")
    @GetMapping("/checklists/{checklistId}")
    public ResponseEntity<ChecklistResponseDto> getChecklist(@PathVariable Integer checklistId) {
        return ResponseEntity.ok(checklistService.getChecklist(checklistId));
    }

    @Operation(summary = "체크리스트 수정", description = "체크리스트 제목/점검 단계를 수정함")
    @PutMapping("/checklists/{checklistId}")
    public ResponseEntity<ChecklistResponseDto> updateChecklist(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Integer checklistId,
            @Valid @RequestBody ChecklistUpdateRequestDto dto) {
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(checklistService.updateChecklist(userId, checklistId, dto));
    }

    @Operation(summary = "체크리스트 삭제", description = "점검 기록이 없는 체크리스트만 삭제 가능함")
    @DeleteMapping("/checklists/{checklistId}")
    public ResponseEntity<Void> deleteChecklist(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Integer checklistId) {
        String userId = customUserDetails.getMember().getUserId();
        checklistService.deleteChecklist(userId, checklistId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "체크리스트 항목 추가", description = "체크리스트에 항목을 추가함")
    @PostMapping("/checklists/{checklistId}/items")
    public ResponseEntity<ChecklistItemSimpleResponseDto> addItem(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Integer checklistId,
            @Valid @RequestBody ChecklistItemRequestDto dto) {
        String userId = customUserDetails.getMember().getUserId();
        ChecklistItemSimpleResponseDto response = checklistService.addItem(userId, checklistId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "체크리스트 항목 수정", description = "체크리스트 항목의 내용/순서를 수정함")
    @PutMapping("/checklists/{checklistId}/items/{itemId}")
    public ResponseEntity<ChecklistItemSimpleResponseDto> updateItem(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Integer checklistId,
            @PathVariable Integer itemId,
            @Valid @RequestBody ChecklistItemUpdateRequestDto dto) {
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(checklistService.updateItem(userId, checklistId, itemId, dto));
    }

    @Operation(summary = "체크리스트 항목 삭제", description = "점검 기록이 없는 항목만 삭제 가능함")
    @DeleteMapping("/checklists/{checklistId}/items/{itemId}")
    public ResponseEntity<Void> deleteItem(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Integer checklistId,
            @PathVariable Integer itemId) {
        String userId = customUserDetails.getMember().getUserId();
        checklistService.deleteItem(userId, checklistId, itemId);
        return ResponseEntity.noContent().build();
    }
}
