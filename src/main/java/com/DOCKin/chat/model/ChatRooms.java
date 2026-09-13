package com.DOCKin.chat.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="chat_rooms")
public class ChatRooms {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="room_id")
    private Integer roomId;

    @Column(name="room_name")
    private String roomName;

    @Column(name="is_group")
    private Boolean isGroup;

    @Column(name="creator_id")
    private String creatorId;

    @Column(name="created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_message_content")
    private String lastMessageContent;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    /**
     * 이 방에서 마지막으로 발급된 {@code room_seq}(V6). 발급은 {@code ChatJdbcRepository.nextRoomSeq}가
     * 행 락 안에서 {@code +1 RETURNING}으로 하며, 엔티티는 읽기 전용으로만 든다 — 같은 트랜잭션에서
     * 올린 이 객체는 발급 뒤에도 옛 값이다. 안읽음은 {@code last_message_seq - last_read_seq}, 두 정수의 차다.
     */
    @Builder.Default
    @Column(name = "last_message_seq", nullable = false)
    private Long lastMessageSeq = 0L;

    @Builder.Default
    @OneToMany(mappedBy = "chatRooms", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, orphanRemoval = true)
    private List<ChatMembers> members = new ArrayList<>();

    @PrePersist
    public void prePersist(){
        this.createdAt=LocalDateTime.now();
        if(this.isGroup==null) this.isGroup=false;
    }

    public void updateRoomName(String room_name){
        if(room_name==null || room_name.isBlank()){
            throw new IllegalArgumentException("방 이름은 필수입니다.");
        }
        this.roomName=room_name;
    }

    public void removeMember(String userId){
        this.members.removeIf(m->m.getMember().getUserId().equals(userId));
    }


}
