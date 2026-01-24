package com.DOCKin.repository.WorkLogs;

import com.DOCKin.model.WorkLogs.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment,Long> {
    List<Comment> findAllByLogId_LogId(Long logId);
}
