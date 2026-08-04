package com.DOCKin.attendance.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 근무일 캘린더.
 *
 * <p><b>이 테이블이 없으면 결근 배치가 공휴일에 전원을 결근 처리한다.</b>
 * {@code WorkShift}는 교대 시간대만 정의하고 휴무일 정보가 어디에도 없었다.
 *
 * <p><b>날짜를 PK로 쓴다(자연키).</b> 같은 날짜가 두 번 등록될 수 없어야 하는데,
 * 대리키를 두고 유니크 제약을 따로 거는 것보다 자연키가 의도를 직접 드러낸다.
 *
 * <p><b>등록되지 않은 날은 기본 규칙을 따른다.</b> 캘린더를 비워둬도 기존 동작(주말만 제외)이
 * 그대로 유지되므로 점진적으로 채워 넣을 수 있다. 반대로 "미등록 = 휴무"로 잡으면
 * 캘린더를 채우기 전까지 결근 배치가 조용히 무력화된다.
 *
 * <h3>범위 한계</h3>
 * 이 캘린더는 <b>전사 공통</b> 휴무일만 다룬다. 조선소는 교대조마다 휴무 패턴이 달라
 * 실제로는 {@code (날짜, 교대조)} 단위로 근무일이 결정되지만, 그건 근무 정책 엔진의 영역이다
 * (ADR-0005 "근무 정책 엔진 미충족", 백로그 P3). 여기까지만으로도
 * "공휴일에 전원 결근" 사고는 막힌다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "work_calendar")
public class WorkCalendar {

    @Id
    @Column(name = "calendar_date")
    private LocalDate calendarDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", length = 20, nullable = false)
    private DayType dayType;

    @Column(name = "description", length = 100)
    private String description;

    @Builder
    public WorkCalendar(LocalDate calendarDate, DayType dayType, String description) {
        this.calendarDate = calendarDate;
        this.dayType = dayType;
        this.description = description;
    }

    public void update(DayType dayType, String description) {
        this.dayType = dayType;
        this.description = description;
    }

    /** 근무일인지. {@link DayType#WORKDAY}만 근무일이며 나머지는 모두 휴무다. */
    public boolean isWorkingDay() {
        return dayType == DayType.WORKDAY;
    }
}
