package com.DOCKin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class WorkLogsCreateRequestDto {
    private String title;
    private String log_text;

    private Long equipmentId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
