package com.DOCKin.safetyCourse.repository;

import com.DOCKin.safetyCourse.model.SafetyEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SafetyEnrollmentRepository extends JpaRepository<SafetyEnrollment, Integer> {

    List<SafetyEnrollment> findAllByUserIdUserId(String userId);

    Optional<SafetyEnrollment> findByUserIdUserIdAndCourseIdCourseId(String userId, Integer courseId);

    /** 삭제 전 검사(#107). 수강 기록이 하나라도 있으면 그 교육은 지울 수 없다 — FK가 NO ACTION이라 지우면 500이었다. */
    boolean existsByCourseIdCourseId(Integer courseId);

    /**
     * 시청 완료를 한 문장으로 기록한다 (#141). 없으면 WATCHED로 넣고, 있으면 WATCHED로 바꾼다.
     *
     * <p>예전에는 "찾고, 없으면 엔티티를 만들어 save"였다. 같은 요청이 동시에 두 번 오면 둘 다 "없음"을 보고 둘 다 INSERT해
     * 행이 둘이 됐다. 충돌 판정을 DB에 맡기면({@code ON CONFLICT}, V19의 유니크가 그 대상) 동시 요청도 한 행으로 수렴하고,
     * 예외를 잡아 다시 시도하는 코드가 필요 없다.
     *
     * <p>완료일은 예전과 같이 마지막 완료 시각으로 덮어쓴다. 최초 이수일을 남겨야 하는지는 정하지 않았다(PR 본문).
     *
     * <p>네이티브 쿼리인 이유: JPQL에 {@code ON CONFLICT}가 없다. 영속성 컨텍스트를 거치지 않으므로, 같은 트랜잭션에서
     * 이 행을 엔티티로 이미 읽었다면 그 엔티티는 낡는다 — 호출하는 쪽({@code completedViewVideo})은 읽지 않는다.
     *
     * @return 영향받은 행 수(항상 1)
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO safety_enrollments (user_id, course_id, status, completion_date, enrolled_at)
            VALUES (:userId, :courseId, 'WATCHED', :now, :now)
            ON CONFLICT (user_id, course_id)
            DO UPDATE SET status = 'WATCHED', completion_date = EXCLUDED.completion_date
            """)
    int upsertWatched(@Param("userId") String userId, @Param("courseId") Integer courseId,
                      @Param("now") LocalDateTime now);
}
