package com.DOCKin.worklog.controller;

import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.worklog.dto.WorkLogApproveRequestDto;
import com.DOCKin.worklog.dto.WorkLogCursor;
import com.DOCKin.worklog.dto.WorkLogDto;
import com.DOCKin.worklog.dto.WorkLogRejectRequestDto;
import com.DOCKin.worklog.model.WorkLogStatus;
import com.DOCKin.worklog.service.WorkLogReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 관리자용 작업일지 승인·반려 (P2-17-1). 경로가 {@code /api/work-logs/admin/**}라
 * {@code SecurityConfig}의 {@code /api/*}{@code /admin/**} → ADMIN에 걸린다.
 */
@Tag(name = "관리자용 작업일지 검토", description = "같은 구역 작업일지를 승인/반려한다")
@RestController
@RequestMapping("/api/work-logs/admin")
@RequiredArgsConstructor
public class WorkLogAdminController {

    private final WorkLogReviewService workLogReviewService;

    @Operation(summary = "검토 목록", description = "같은 구역의 작업일지를 상태별로 최신순. status 기본값 PENDING. "
            + "페이지 규칙은 GET /api/work-logs와 같다(beforeCreatedAt·beforeLogId 커서)")
    @GetMapping
    public ResponseEntity<Slice<WorkLogDto>> list(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam(required = false) WorkLogStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beforeCreatedAt,
            @RequestParam(required = false) Long beforeLogId,
            @PageableDefault(size = 20, sort = {"createdAt", "logId"}, direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(workLogReviewService.list(customUserDetails.getMember().getUserId(), status,
                WorkLogCursor.of(beforeCreatedAt, beforeLogId), pageable));
    }

    @Operation(summary = "승인", description = "PENDING만. 코멘트는 선택. 다른 구역이면 403, 이미 검토됐으면 409")
    @PatchMapping("/{logId}/approve")
    public ResponseEntity<WorkLogDto> approve(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Long logId,
            @RequestBody(required = false) @Valid WorkLogApproveRequestDto dto) {
        String comment = dto == null ? null : dto.comment();
        return ResponseEntity.ok(workLogReviewService.approve(customUserDetails.getMember().getUserId(), logId, comment));
    }

    @Operation(summary = "반려", description = "PENDING만. 사유 필수. 작성자가 수정하면 다시 PENDING이 된다")
    @PatchMapping("/{logId}/reject")
    public ResponseEntity<WorkLogDto> reject(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Long logId,
            @RequestBody @Valid WorkLogRejectRequestDto dto) {
        return ResponseEntity.ok(workLogReviewService.reject(customUserDetails.getMember().getUserId(), logId, dto.comment()));
    }
}
