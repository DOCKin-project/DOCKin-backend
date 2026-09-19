package com.DOCKin.chat.model;

import com.DOCKin.member.model.Member;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name="chat_members")
public class ChatMembers {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="id")
    private Integer id;

    @JsonBackReference
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private ChatRooms chatRooms;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id",nullable = false)
    private Member member;

    @Column(name="joined_at")
    private LocalDateTime joinedAt;

    /**
     * 이 멤버가 읽은 마지막 {@code room_seq}(V6, ADR-0008 D7). 0은 "아무것도 안 읽음"이다.
     * 시각 기준이던 {@code last_read_time}은 V7이 내렸다 — 읽는 곳이 0이 된 뒤다. 올리는 곳은
     * {@code ChatJdbcRepository.markRead} 하나이며 엔티티는 읽기 전용으로만 든다.
     */
    @Builder.Default
    @Column(name = "last_read_seq", nullable = false)
    private Long lastReadSeq = 0L;

    @PrePersist
    public void prePersist(){
        this.joinedAt=LocalDateTime.now();
    }
}
