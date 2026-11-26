package com.DOCKin.safetyCourse.model;

import com.DOCKin.safetyCourse.dto.CompletedLabel;
import com.DOCKin.member.model.Member;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
// (user_id, course_id) 유니크는 V19가 만든다(#141). 여기 적는 것은 스키마를 읽는 사람을 위해서다 — validate는 유니크를 보지 않는다.
@Table(name = "safety_enrollments", uniqueConstraints = @UniqueConstraint(
        name = "uk_safety_enrollment_user_course", columnNames = {"user_id", "course_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SafetyEnrollment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "enrollment_id")
    private Integer enrollmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Member userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private SafetyCourse courseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private CompletedLabel status = CompletedLabel.UNWATCHED;

    @Column(name = "completion_date")
    private LocalDateTime completionDate;

    @CreationTimestamp
    @Column(name = "enrolled_at", updatable = false)
    private LocalDateTime enrolledAt;


    public void updateStatus(){
        status = CompletedLabel.WATCHED;
            this.completionDate = LocalDateTime.now();

    }
}