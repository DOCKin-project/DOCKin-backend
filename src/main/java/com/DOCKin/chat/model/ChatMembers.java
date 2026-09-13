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

    @Column(name="last_read_time")
    private LocalDateTime lastReadTime;

    /**
     * 이 멤버가 읽은 마지막 {@code room_seq}(V6, ADR-0008 D7). 0은 "아무것도 안 읽음"이다.
     * {@code lastReadTime}은 한 릴리스 동안 병행하고 V7에서 내린다 — 읽음 API가 seq를 쓰게 바뀐 뒤.
     */
    @Builder.Default
    @Column(name = "last_read_seq", nullable = false)
    private Long lastReadSeq = 0L;

    @PrePersist
    public void prePersist(){
        this.joinedAt=LocalDateTime.now();
        this.lastReadTime= LocalDateTime.now();
    }

    public void updateLastReadTime(LocalDateTime lastReadTime) {
        this.lastReadTime = lastReadTime;
    }
}
