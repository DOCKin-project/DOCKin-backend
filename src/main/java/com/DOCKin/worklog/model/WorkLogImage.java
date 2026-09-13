package com.DOCKin.worklog.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "work_log_images")
public class WorkLogImage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String imageUrl;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name = "work_log_id")
    private WorkLog workLog;

    @Builder
    public WorkLogImage(String imageUrl, WorkLog workLog){
        this.imageUrl=imageUrl;
        this.workLog=workLog;
    }
}
