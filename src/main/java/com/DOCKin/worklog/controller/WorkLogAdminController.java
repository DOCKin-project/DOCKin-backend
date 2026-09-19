package com.DOCKin.worklog.controller;

import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.worklog.dto.WorkLogDecisionRequestDto;
import com.DOCKin.worklog.dto.WorkLogDto;
import com.DOCKin.worklog.service.WorkLogReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 작업일지 승인·반려 (P2-17-1). 경로가 {@code /api/*}{@code /admin/**}라 {@code SecurityConfig}가
 * ADMIN이 아니면 여기 오기 전에 403을 낸다. 미승인 큐는 별도 목록이 아니라
 * {@code GET /api/work-logs?status=PENDING}이다 — 관리자가 볼 범위도 결국 같은 구역이라
 * 그 쿼리에 필터만 붙였다.
 */
@Tag(name = "관리자용 작업일지 승인", description = "작업일지를 승인/반려할 수 있는 api")
@RestController
@RequestMapping("/api/work-logs/admin")
@RequiredArgsConstructor
public class WorkLogAdminController {

    private final WorkLogReviewService workLogReviewService;

    @Operation(summary = "작업일지 승인",
            description = "PENDING인 작업일지를 승인함. 이미 검토됐으면 409. 작성자가 수정하면 다시 PENDING")
    @PatchMapping("/{logId}/approve")
    public ResponseEntity<WorkLogDto> approve(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Long logId,
            @RequestBody(required = false) @Valid WorkLogDecisionRequestDto dto) {
        String adminUserId = customUserDetails.getMember().getUserId();
        String comment = dto != null ? dto.getComment() : null;
        return ResponseEntity.ok(workLogReviewService.approve(adminUserId, logId, comment));
    }

    @Operation(summary = "작업일지 반려", description = "PENDING인 작업일지를 반려함. 이미 검토됐으면 409")
    @PatchMapping("/{logId}/reject")
    public ResponseEntity<WorkLogDto> reject(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Long logId,
            @RequestBody(required = false) @Valid WorkLogDecisionRequestDto dto) {
        String adminUserId = customUserDetails.getMember().getUserId();
        String comment = dto != null ? dto.getComment() : null;
        return ResponseEntity.ok(workLogReviewService.reject(adminUserId, logId, comment));
    }
}
