package com.DOCKin.absence.controller;

import com.DOCKin.absence.dto.AbsenceDecisionRequestDto;
import com.DOCKin.absence.dto.AbsenceRequestResponseDto;
import com.DOCKin.absence.model.AbsenceStatus;
import com.DOCKin.absence.service.AbsenceRequestService;
import com.DOCKin.global.security.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "관리자용 휴가 승인", description = "휴가 신청을 승인/거절할 수 있는 api")
@Slf4j
@RestController
@RequestMapping("/api/absence/admin")
@RequiredArgsConstructor
public class AbsenceAdminController {

    private final AbsenceRequestService absenceRequestService;

    @Operation(summary = "휴가 신청 목록 조회", description = "상태별(또는 전체) 휴가 신청 목록을 조회함")
    @GetMapping("/requests")
    public ResponseEntity<Page<AbsenceRequestResponseDto>> getRequests(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam(required = false) AbsenceStatus status,
            @PageableDefault(size = 20, sort = "requestId", direction = Sort.Direction.ASC) Pageable pageable) {
        String adminUserId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(absenceRequestService.getRequestsForAdmin(adminUserId, status, pageable));
    }

    @Operation(summary = "휴가 신청 승인", description = "휴가 신청을 승인함 (연차인 경우 잔여 연차 차감)")
    @PatchMapping("/requests/{requestId}/approve")
    public ResponseEntity<AbsenceRequestResponseDto> approveRequest(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Integer requestId,
            @RequestBody(required = false) AbsenceDecisionRequestDto dto) {
        String adminUserId = customUserDetails.getMember().getUserId();
        String comment = dto != null ? dto.getComment() : null;
        return ResponseEntity.ok(absenceRequestService.approveRequest(adminUserId, requestId, comment));
    }

    @Operation(summary = "휴가 신청 거절", description = "휴가 신청을 거절함")
    @PatchMapping("/requests/{requestId}/reject")
    public ResponseEntity<AbsenceRequestResponseDto> rejectRequest(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Integer requestId,
            @RequestBody(required = false) AbsenceDecisionRequestDto dto) {
        String adminUserId = customUserDetails.getMember().getUserId();
        String comment = dto != null ? dto.getComment() : null;
        return ResponseEntity.ok(absenceRequestService.rejectRequest(adminUserId, requestId, comment));
    }
}
