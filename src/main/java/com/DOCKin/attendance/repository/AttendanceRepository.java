package com.DOCKin.attendance.repository;

import com.DOCKin.attendance.model.Attendance;
import com.DOCKin.member.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance,Long> {
    List<Attendance> findByMemberOrderByWorkDateDesc(Member member);
    Optional<Attendance> findByMemberAndWorkDate(Member member, LocalDate workDate);

    /**
     * 휴가 승인 시 해당 기간에 이미 존재하는 근태 기록을 한 번에 조회한다.
     * 날짜마다 개별 조회하면 기간이 길수록 쿼리가 그만큼 늘어난다.
     */
    List<Attendance> findByMemberAndWorkDateBetween(Member member, LocalDate startDate, LocalDate endDate);

    /**
     * 자정 결근 배치용. 해당 날짜에 기록이 <b>있는</b> 사번 목록을 가져온다.
     * 전체 인원에서 이 목록을 빼면 결근 대상이 된다.
     *
     * <p>Attendance 엔티티가 아니라 사번만 투영하는 이유: 5,000명 규모에서 엔티티를 전부
     * 적재할 이유가 없고, 필요한 것은 "기록이 있는가"뿐이다.
     */
    @Query("SELECT a.member.userId FROM Attendance a WHERE a.workDate = :workDate")
    List<String> findUserIdsByWorkDate(@Param("workDate") LocalDate workDate);
}
