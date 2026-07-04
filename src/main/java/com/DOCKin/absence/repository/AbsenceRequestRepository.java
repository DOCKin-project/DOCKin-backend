package com.DOCKin.absence.repository;

import com.DOCKin.absence.model.AbsenceRequest;
import com.DOCKin.absence.model.AbsenceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AbsenceRequestRepository extends JpaRepository<AbsenceRequest, Integer> {
    Page<AbsenceRequest> findByMember_UserIdOrderByRequestedAtDesc(String userId, Pageable pageable);
    Page<AbsenceRequest> findByStatusOrderByRequestedAtAsc(AbsenceStatus status, Pageable pageable);
}
