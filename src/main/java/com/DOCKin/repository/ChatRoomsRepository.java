package com.DOCKin.repository;

import com.DOCKin.model.Chat.ChatRooms;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomsRepository extends JpaRepository<ChatRooms,Integer> {
}
