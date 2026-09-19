package com.DOCKin.attendance.controller;

import com.DOCKin.attendance.service.AttendanceService;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.member.dto.CustomUserInfoDto;
import com.DOCKin.member.model.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검증: {@code GET /api/attendance?from=&to=}의 파라미터 해석 (P2-20-6).
 *
 * <p>기간 규칙(기본값·상한·역순)은 {@code AttendanceServiceTest}가 본다. 여기는 그 앞 — 문자열이
 * {@code LocalDate}가 되는지, 안 주면 null로 가는지, 깨진 날짜가 500이 아니라 400인지만 본다.
 * 서비스는 목이다.
 */
@WebMvcTest(AttendanceController.class)
@DisplayName("근태 조회 파라미터 - from·to는 ISO 날짜, 없으면 null, 깨지면 400")
class AttendanceRangeParamTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttendanceService attendanceService;

    @Test
    @DisplayName("from·to를 ISO 날짜로 받아 그대로 서비스에")
    void isoDatesPassThrough() throws Exception {
        when(attendanceService.getMyAttendanceRecords(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/attendance").with(user(principal("worker01")))
                        .param("from", "2026-07-01").param("to", "2026-07-31"))
                .andExpect(status().isOk());

        verify(attendanceService).getMyAttendanceRecords(
                eq("worker01"), eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 31)));
    }

    @Test
    @DisplayName("둘 다 없으면 null·null - 기본 기간은 서비스가 정한다")
    void missingBecomesNull() throws Exception {
        when(attendanceService.getMyAttendanceRecords(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/attendance").with(user(principal("worker01"))))
                .andExpect(status().isOk());

        verify(attendanceService).getMyAttendanceRecords(eq("worker01"), isNull(), isNull());
    }

    @Test
    @DisplayName("깨진 날짜(2026-13-01)는 400 - 타입 불일치 핸들러가 받는다")
    void malformedDateIs400() throws Exception {
        mockMvc.perform(get("/api/attendance").with(user(principal("worker01")))
                        .param("from", "2026-13-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(attendanceService, never()).getMyAttendanceRecords(any(), any(), any());
    }

    private static CustomUserDetails principal(String userId) {
        return new CustomUserDetails(CustomUserInfoDto.builder()
                .userId(userId)
                .name("파라미터 테스트")
                .password("n/a")
                .role(UserRole.USER)
                .build());
    }
}
