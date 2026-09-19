package com.DOCKin.attendance.dto;

import com.DOCKin.attendance.model.DayType;
import com.DOCKin.attendance.model.WorkCalendar;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 연초 공휴일 목록 일괄 upsert 본문.
 *
 * <p>순서대로 처리하므로 같은 날짜가 두 번이면 뒤가 이긴다. 최대 366 — 한 해치를 넘는 요청은
 * 실수일 가능성이 높고, 그 이상은 나눠 보내면 된다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "근무일 캘린더 일괄 등록·갱신")
public class WorkCalendarBulkUpsertRequestDto {

    @Schema(description = "등록할 날짜들 (1~366)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "entries는 비어 있을 수 없습니다.")
    @Size(max = 366, message = "entries는 최대 366건입니다.")
    @Valid
    private List<Entry> entries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "일괄 등록의 한 날")
    public static class Entry {

        @Schema(description = "날짜", example = "2026-10-03", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "date는 필수입니다.")
        private LocalDate date;

        @Schema(description = "날짜 구분", example = "HOLIDAY", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "dayType은 필수입니다.")
        private DayType dayType;

        @Schema(description = "설명 (최대 100자)", example = "개천절")
        @Size(max = 100, message = "description은 100자 이하입니다.")
        private String description;

        public WorkCalendar toEntity() {
            return WorkCalendar.builder()
                    .calendarDate(date)
                    .dayType(dayType)
                    .description(description)
                    .build();
        }
    }
}
