package com.DOCKin.checklist.model;

import com.DOCKin.worklog.model.Equipment;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "checklists", uniqueConstraints = @UniqueConstraint(
        name = "uk_checklist_equipment_phase", columnNames = {"equipment_id", "phase"}))
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Checklist {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "checklist_id")
    private Integer checklistId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "equipment_id", nullable = false)
    private Equipment equipment;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    // schema.sql의 원래 컬럼명은 role이었으나 Member.role과 의미가 겹쳐 phase로 변경
    @Enumerated(EnumType.STRING)
    @Column(name = "phase", length = 10, nullable = false)
    private ChecklistPhase phase;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
