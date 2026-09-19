package com.DOCKin.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.DOCKin.member.model.WorkShift;

import java.time.LocalDate;

/**
 * 관리자 대시보드의 하루 인원 집계 (P2-17-4). PPT 17P "출근 124명 / 퇴근 52명 / 휴가 8명 / 병결 2명".
 *
 * <p>구역 인원 하나에 근태 행 하나를 왼쪽 조인한 결과라 <b>{@code headcount}는 근태 행이 없는 사람까지</b> 센다.
 * 앱은 {@code headcount - clockedIn - vacation - sick - absent}로 "아직 안 찍은 사람"을 낼 수 있다.
 * {@code absent}는 자정 배치({@code AttendanceBatchService})가 채우므로 오늘 날짜에서는 항상 0이다.
 * {@code clockedOut}·{@code late}는 {@code clockedIn}의 부분집합이다. {@code workShift}는 필터를 그대로 돌려준다 — null이면 전 근무조.
 */
@Schema(description = "구역·날짜 기준 근태 인원 집계 res dto")
public record AttendanceDailySummaryDto(
        @Schema(description = "집계 날짜", example = "2026-09-18") LocalDate date,
        @Schema(description = "구역", example = "A구역") String shipYardArea,
        @Schema(description = "근무조 필터. null이면 전 근무조", example = "MORNING") WorkShift workShift,
        @Schema(description = "구역 인원 전체 (근태 행 유무와 무관)", example = "150") long headcount,
        @Schema(description = "출근 찍은 사람 (clock_in_time 있음)", example = "124") long clockedIn,
        @Schema(description = "퇴근까지 찍은 사람 (clock_out_time 있음). clockedIn의 부분집합", example = "52") long clockedOut,
        @Schema(description = "지각 (LATE). clockedIn의 부분집합", example = "5") long late,
        @Schema(description = "휴가 (VACATION)", example = "8") long vacation,
        @Schema(description = "병결 (SICK)", example = "2") long sick,
        @Schema(description = "결근 (ABSENT). 자정 배치가 채우므로 오늘은 0", example = "0") long absent
) {
}
