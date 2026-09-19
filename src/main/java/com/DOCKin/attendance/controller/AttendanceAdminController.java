package com.DOCKin.attendance.controller;

import com.DOCKin.attendance.dto.AttendanceDailySummaryDto;
import com.DOCKin.attendance.service.AttendanceService;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.member.model.WorkShift;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 관리자 근태 (P2-17-4). 경로가 {@code /api/*}{@code /admin/**}라 {@code SecurityConfig}가 ADMIN이 아니면
 * 여기 오기 전에 403을 낸다. {@code GET /api/attendance}(개인 기록)와 달리 <b>오늘 하루의 숫자</b>다 —
 * P3의 월말 집계와 다르다.
 */
@Tag(name = "관리자용 근태", description = "구역·날짜 기준 근태 인원 집계")
@RestController
@RequestMapping("/api/attendance/admin")
@RequiredArgsConstructor
public class AttendanceAdminController {

    private final AttendanceService attendanceService;

    @Operation(summary = "하루 인원 집계",
            description = "구역 인원 전체 / 출근 / 퇴근 / 지각 / 휴가 / 병결 / 결근. date(yyyy-MM-dd) 생략은 오늘, "
                    + "shipYardArea 생략은 관리자 자신의 구역, workShift(MORNING/AFTERNOON/NIGHT) 생략은 전 근무조. "
                    + "결근은 자정 배치가 채우므로 오늘은 0")
    @GetMapping("/daily-summary")
    public ResponseEntity<AttendanceDailySummaryDto> dailySummary(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String shipYardArea,
            @RequestParam(required = false) WorkShift workShift) {
        String adminUserId = userDetails.getMember().getUserId();
        return ResponseEntity.ok(attendanceService.getDailySummary(adminUserId, date, shipYardArea, workShift));
    }
}
