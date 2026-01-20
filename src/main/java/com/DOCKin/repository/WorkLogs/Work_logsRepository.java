package com.DOCKin.repository.WorkLogs;

import com.DOCKin.model.Member.Member;
import com.DOCKin.model.WorkLogs.Work_logs;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface Work_logsRepository extends JpaRepository<Work_logs, Long> {
    @Transactional
    Page<Work_logs> findByMemberIn(List<Member> members, Pageable pageable);
    Page<Work_logs> findAllByMember_UserId(String targetUserId, Pageable pageable);
}
