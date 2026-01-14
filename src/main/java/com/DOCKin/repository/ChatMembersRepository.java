package com.DOCKin.repository;

import com.DOCKin.model.Chat.ChatMembers;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMembersRepository extends JpaRepository<ChatMembers,Integer> {
}
