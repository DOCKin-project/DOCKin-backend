package com.DOCKin.attendance.controller;

import com.DOCKin.attendance.dto.WorkCalendarDto;
import com.DOCKin.attendance.service.WorkCalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Year;
import java.util.List;

/**
 * 근무일 캘린더 조회. 로그인한 전원이 본다 — 근로자 앱도 공휴일을 보여주고, 민감한 것이 없다.
 * 등록·삭제는 {@link WorkCalendarAdminController}.
 */
@Tag(name = "근무일 캘린더", description = "공휴일·특근일 조회. 등록·삭제는 /api/attendance/admin/calendar")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/attendance/calendar")
public class WorkCalendarController {

    private final WorkCalendarService workCalendarService;
    private final Clock clock;

    @Operation(summary = "연간 캘린더 조회",
            description = "등록된 날만 날짜순. year 생략은 올해. 등록되지 않은 날은 기본 규칙(평일=근무, 주말=휴무)")
    @GetMapping
    public ResponseEntity<List<WorkCalendarDto>> getYear(@RequestParam(required = false) Integer year) {
        int resolved = year != null ? year : Year.now(clock).getValue();
        List<WorkCalendarDto> body = workCalendarService.findByYear(resolved).stream()
                .map(WorkCalendarDto::fromEntity)
                .toList();
        return ResponseEntity.ok(body);
    }
}
