package com.DOCKin.member.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access= AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name="users")
public class Member {
    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(nullable=false, length = 10)
    private String name;

    @Column(nullable=false, length = 256)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable=false)
    private UserRole role;

    @Column(nullable=false)
    private String language_code;

    @Column(nullable=false)
    private Boolean tts_enabled;

    @CreationTimestamp
    @Column(updatable=false, nullable = false)
    private LocalDateTime created_at;

    @Column(nullable = false, length = 100)
    private String shipYardArea;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private WorkShift workShift = WorkShift.MORNING;

    // 연차 정책 확정 전까지의 잠정 기본값 (WorkShift와 동일한 패턴)
    @Column(name = "remaining_leave_days", nullable = false)
    @Builder.Default
    private Integer remainingLeaveDays = 15;

    public void useLeaveDays(int days) {
        this.remainingLeaveDays -= days;
    }

}
