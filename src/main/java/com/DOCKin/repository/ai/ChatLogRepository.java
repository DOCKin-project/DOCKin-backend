package com.DOCKin.repository.ai;

import com.DOCKin.model.ai.ChatHistory;
import com.DOCKin.model.ai.ChatLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatLogRepository extends JpaRepository<ChatLog,Long> {
}
