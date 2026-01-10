package com.DOCKin.dto.SafetyCourse;

import lombok.Getter;

@Getter
public class SafetyCourseCreateRequest {
    private Integer courseId;
    private String title;
    private String description;
    private String videoUrl;
    private Integer durationMinutes;
}
