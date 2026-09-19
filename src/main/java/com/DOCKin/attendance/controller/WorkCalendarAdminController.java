package com.DOCKin.attendance.controller;

import com.DOCKin.attendance.dto.WorkCalendarBulkUpsertRequestDto;
import com.DOCKin.attendance.dto.WorkCalendarDto;
import com.DOCKin.attendance.dto.WorkCalendarUpsertRequestDto;
import com.DOCKin.attendance.model.WorkCalendar;
import com.DOCKin.attendance.service.WorkCalendarService;
import com.DOCKin.global.security.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 근무일 캘린더 등록·삭제. {@code /api/*}{@code /admin/**}라 경로 규칙이 ADMIN만 통과시키고,
 * 서비스가 한 번 더 검사한다. 조회는 {@link WorkCalendarController}.
 *
 * <p>등록은 PUT이다 — 날짜가 PK라 등록과 갱신이 같은 요청이고, 두 번 보내도 같다.
 */
@Tag(name = "관리자용 근무일 캘린더", description = "공휴일·특근일 등록(upsert)·삭제. 조회는 /api/attendance/calendar")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/attendance/admin/calendar")
public class WorkCalendarAdminController {

    private final WorkCalendarService workCalendarService;

    @Operation(summary = "한 날 등록·갱신",
            description = "날짜가 PK라 upsert. 주말에 WORKDAY면 특근일, 평일에 HOLIDAY/COMPANY_HOLIDAY면 휴무")
    @PutMapping("/{date}")
    public ResponseEntity<WorkCalendarDto> upsert(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody WorkCalendarUpsertRequestDto request) {
        String adminUserId = userDetails.getMember().getUserId();
        WorkCalendar saved = workCalendarService.register(
                adminUserId, date, request.getDayType(), request.getDescription());
        return ResponseEntity.ok(WorkCalendarDto.fromEntity(saved));
    }

    @Operation(summary = "일괄 등록·갱신",
            description = "연초 공휴일 목록용. 순서대로 upsert라 같은 날짜가 두 번이면 뒤가 이긴다. 최대 366")
    @PutMapping
    public ResponseEntity<List<WorkCalendarDto>> upsertAll(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody WorkCalendarBulkUpsertRequestDto request) {
        String adminUserId = userDetails.getMember().getUserId();
        List<WorkCalendar> entries = request.getEntries().stream()
                .map(WorkCalendarBulkUpsertRequestDto.Entry::toEntity)
                .toList();
        List<WorkCalendarDto> body = workCalendarService.registerAll(adminUserId, entries).stream()
                .map(WorkCalendarDto::fromEntity)
                .toList();
        return ResponseEntity.ok(body);
    }

    @Operation(summary = "한 날 삭제",
            description = "그 날은 기본 규칙(평일=근무, 주말=휴무)으로 돌아간다. 등록되지 않은 날은 404(AT005)")
    @DeleteMapping("/{date}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        workCalendarService.delete(userDetails.getMember().getUserId(), date);
        return ResponseEntity.noContent().build();
    }
}
