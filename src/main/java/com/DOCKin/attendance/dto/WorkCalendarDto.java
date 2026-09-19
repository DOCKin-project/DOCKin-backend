package com.DOCKin.attendance.dto;

import com.DOCKin.attendance.model.DayType;
import com.DOCKin.attendance.model.WorkCalendar;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "근무일 캘린더 한 날")
public class WorkCalendarDto {

    @Schema(description = "날짜", example = "2026-10-03", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate date;

    @Schema(description = "날짜 구분", example = "HOLIDAY", requiredMode = Schema.RequiredMode.REQUIRED)
    private DayType dayType;

    @Schema(description = "설명", example = "개천절")
    private String description;

    @Schema(description = "근무일인지. WORKDAY만 true", requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean workingDay;

    public static WorkCalendarDto fromEntity(WorkCalendar entity) {
        return WorkCalendarDto.builder()
                .date(entity.getCalendarDate())
                .dayType(entity.getDayType())
                .description(entity.getDescription())
                .workingDay(entity.isWorkingDay())
                .build();
    }
}
