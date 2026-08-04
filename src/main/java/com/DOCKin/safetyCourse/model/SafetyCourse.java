package com.DOCKin.safetyCourse.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "safety_courses")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SafetyCourse {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "course_id")
    private Integer courseId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "video_url", nullable = false)
    private String videoUrl;

    @Column(name = "material_url")
    private String materialUrl;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    /**
     * <p>{@code columnDefinition = "DATETIME"}을 제거했다. MySQL 전용 타입명이라
     * PostgreSQL 이관 시 {@code type "datetime" does not exist}로 <b>테이블 생성이 실패했다.</b>
     * {@code ddl-auto=update}는 DDL 오류를 로그만 남기고 기동을 막지 않아,
     * {@code safety_courses} 테이블이 없는 채로 앱이 정상 기동한 것처럼 보였다.
     *
     * <p>타입을 명시하지 않으면 Hibernate가 방언에 맞는 타입을 고른다
     * (MySQL {@code datetime(6)} / PostgreSQL {@code timestamp(6)}).
     * 특정 타입을 강제할 이유가 없다면 비워두는 편이 이식성에 유리하다.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public SafetyCourse(String title, String description, String videoUrl,String materialUrl, Integer durationMinutes, String createdBy) {
        this.title = title;
        this.description = description;
        this.videoUrl = videoUrl;
        this.materialUrl = materialUrl;
        this.durationMinutes = durationMinutes;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }


}