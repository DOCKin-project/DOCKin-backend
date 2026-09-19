package com.DOCKin.attendance.dto;

import com.DOCKin.attendance.model.DayType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 단건 upsert 본문. 날짜는 경로에 있다. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "근무일 캘린더 등록·갱신")
public class WorkCalendarUpsertRequestDto {

    @Schema(description = "날짜 구분. 주말에 WORKDAY면 특근일", example = "HOLIDAY",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "dayType은 필수입니다.")
    private DayType dayType;

    /** 컬럼 길이가 100이다. DB에서 500으로 죽기 전에 400으로 돌려보낸다. */
    @Schema(description = "설명 (최대 100자)", example = "개천절")
    @Size(max = 100, message = "description은 100자 이하입니다.")
    private String description;
}
