package com.DOCKin.safetyCourse.repository;

import com.DOCKin.safetyCourse.model.SafetyEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;


import java.util.List;
import java.util.Optional;

public interface SafetyEnrollmentRepository extends JpaRepository<SafetyEnrollment, Integer> {

    List<SafetyEnrollment> findAllByUserIdUserId(String userId);

    Optional<SafetyEnrollment> findByUserIdUserIdAndCourseIdCourseId(String userId, Integer courseId);

    /** 삭제 전 검사(#107). 수강 기록이 하나라도 있으면 그 교육은 지울 수 없다 — FK가 NO ACTION이라 지우면 500이었다. */
    boolean existsByCourseIdCourseId(Integer courseId);
}
