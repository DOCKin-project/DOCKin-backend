package com.DOCKin.checklist.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "checklist_items")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChecklistItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Integer itemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checklist_id", nullable = false)
    private Checklist checklist;

    @Column(name = "content", nullable = false, length = 255)
    private String content;

    @Column(name = "sequence", nullable = false)
    private Integer sequence;
}
