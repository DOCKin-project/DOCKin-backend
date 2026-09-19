package com.DOCKin.attendance.dto;

import com.DOCKin.attendance.model.Attendance;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description="출퇴 조회용 dto")
public class AttendanceDto {

    @Schema(description = "그냥 조회용",requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "사원번호", example = "1001",requiredMode = Schema.RequiredMode.REQUIRED)
    private String userId;

    /**
     * 근무일 — 교대 기준일(ADR-0010). 야간조는 출근한 날짜와 다를 수 있다(D일 22:00 출근 → 근무일 D, 퇴근은 D+1 06:00).
     * 앱이 "어느 날의 기록인가"를 clockInTime의 날짜로 짐작하면 야간조에서 하루 어긋난다 — 이 필드를 쓴다.
     */
    @Schema(description = "근무일 (교대 기준일. 야간조는 출근 날짜와 다를 수 있음)", example = "2026-07-10", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate workDate;

    @Schema(description = "이 기록을 판정한 교대", example = "NIGHT", requiredMode = Schema.RequiredMode.REQUIRED)
    private String workShift;

    //출근,퇴근 시간
    @Schema(description = "출근한 시간", example = "2026-01-01 09:00:00",requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime clockInTime;

    @Schema(description = "퇴근한 시간", example = "2026-01-01 09:00:00",requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime clockOutTime;

    //근무시간
    /** 저장은 초({@code workSeconds})이고 이 문자열은 그것으로 만든다 — 계약을 지키려고 남긴 표시용 값이다. 퇴근 전에는 null. */
    @Schema(description = "총 근무 시간 (HH:mm:ss 형식, 퇴근 전 null)", example = "08:30:00")
    private String totalWorkTime;

    @Schema(description = "총 근무 시간(초). 합계·평균은 이 값으로. 퇴근 전 null", example = "30600")
    private Integer workSeconds;

    //근로자 상태
    @Schema(description = "근태 상태 (NORMAL, LATE, EARLY_LEAVE 등)", example = "NORMAL",requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;

    //출퇴근장소
    @Schema(description = "출근 장소", example = "제1조선소",requiredMode = Schema.RequiredMode.REQUIRED)
    private String inLocation;

    @Schema(description = "퇴근 장소", example = "제1조선소",requiredMode = Schema.RequiredMode.REQUIRED)
    private String outLocation;

    public static AttendanceDto fromEntity(Attendance saved) {
        return AttendanceDto.builder()
                .id(saved.getId())
                .userId(saved.getMember().getUserId())
                .workDate(saved.getWorkDate())
                .workShift(saved.getWorkShift() != null ? saved.getWorkShift().name() : null)
                .clockInTime(saved.getClockInTime())
                .clockOutTime(saved.getClockOutTime())
                .totalWorkTime(formatSeconds(saved.getWorkSeconds()))
                .workSeconds(saved.getWorkSeconds())
                .status(saved.getStatus().name())
                .inLocation(saved.getInLocation())
                .outLocation(saved.getOutLocation())
                .build();
    }

    /** 초 → "HH:mm:ss". 24시간을 넘어도 시간 자리에 그대로 쌓는다(예: 30시간 → "30:00:00"). */
    static String formatSeconds(Integer seconds) {
        if (seconds == null) return null;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long sec = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, sec);
    }
}
