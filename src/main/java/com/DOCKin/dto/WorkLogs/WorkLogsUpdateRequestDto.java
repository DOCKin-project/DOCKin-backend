package com.DOCKin.dto.WorkLogs;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
//작업일지 수정용
public class WorkLogsUpdateRequestDto {
    private String title;
    private String log_text;
    private String image_url;
    private Long equipmentId;

    //private LocalDateTime createdAt;
    //private LocalDateTime updatedAt;
}
