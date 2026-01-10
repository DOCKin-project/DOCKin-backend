package com.DOCKin.dto.WorkLogs;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
//작업일지 생성용
public class WorkLogsCreateRequestDto {
    private String title;
    private String log_text;
    private String image_url;
    private Long equipmentId;

    //private LocalDateTime createdAt;
    //private LocalDateTime updatedAt;
}
