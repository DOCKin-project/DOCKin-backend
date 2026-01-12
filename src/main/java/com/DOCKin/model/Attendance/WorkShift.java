package com.DOCKin.model.Attendance;

import java.time.LocalTime;

public enum WorkShift {
    MORNING("08:00"),
    AFTERNOON("16:00"),
    NIGHT("22:00");

    private final String startTimeStr;

    WorkShift(String startTimeStr){
        this.startTimeStr = startTimeStr;
    }

    public LocalTime getStartTime() {
        return LocalTime.parse(this.startTimeStr);
    }
}
