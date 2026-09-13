package com.DOCKin.member.model;

import java.time.LocalTime;

// 조선소 현장 3교대 기준 시작 시각 (업무 규정 확정 전까지의 잠정값)
public enum WorkShift {
    MORNING(LocalTime.of(6, 0)),
    AFTERNOON(LocalTime.of(14, 0)),
    NIGHT(LocalTime.of(22, 0));

    private final LocalTime startTime;

    WorkShift(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getStartTime() {
        return startTime;
    }
}
