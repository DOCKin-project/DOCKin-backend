package com.DOCKin.repository;

import com.DOCKin.model.Chat.ChatMessages;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessagesRepository extends JpaRepository<ChatMessages,Long> {
}
