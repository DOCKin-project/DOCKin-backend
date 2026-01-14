package com.DOCKin.model.Chat;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="chat_rooms")
public class ChatRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="room_id")
    private Integer roomId;

    @Column(name="room_name")
    private String roomName;

    @Column(name="is_group")
    private Boolean isGroup;

    @Column(name="created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist(){
        this.createdAt=LocalDateTime.now();
        if(this.isGroup==null) this.isGroup=false;
    }

}
