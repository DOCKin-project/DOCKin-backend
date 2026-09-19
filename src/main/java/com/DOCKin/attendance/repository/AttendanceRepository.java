package com.DOCKin.attendance.repository;

import com.DOCKin.attendance.model.Attendance;
import com.DOCKin.attendance.model.AttendanceStatus;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.WorkShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance,Long> {
    /** 개인 근태 조회. 기간은 서비스가 정한다 — 상한 없이 전부 주던 것을 기간으로 바꿨다(P2-20-6). */
    List<Attendance> findByMemberAndWorkDateBetweenOrderByWorkDateDesc(Member member, LocalDate from, LocalDate to);
    Optional<Attendance> findByMemberAndWorkDate(Member member, LocalDate workDate);

    /**
     * 퇴근 대상 — 이 사람의 <b>열린 기록</b>(출근은 있고 퇴근은 없는) 중 가장 최근 것.
     *
     * <p>날짜로 찾지 않는다. 야간조는 출근한 날과 퇴근하는 날이 다르고, 근무일({@code WorkDay})로 다시 계산해 찾아도
     * 되지만 "무엇을 닫는가"는 결국 열린 기록 하나이므로 그걸 직접 묻는 편이 규칙에 덜 묶인다.
     * {@code clockInTime IS NOT NULL}은 휴가·결근 행(둘 다 null)을 빼기 위해서다. V13의 부분 인덱스
     * {@code idx_attendance_open}이 이 조건 그대로라 사용자당 0~1행만 본다.
     */
    Optional<Attendance> findFirstByMemberAndClockInTimeIsNotNullAndClockOutTimeIsNullOrderByClockInTimeDesc(Member member);

    /**
     * 휴가 승인 시 해당 기간에 이미 존재하는 근태 기록을 한 번에 조회한다.
     * 날짜마다 개별 조회하면 기간이 길수록 쿼리가 그만큼 늘어난다.
     */
    List<Attendance> findByMemberAndWorkDateBetween(Member member, LocalDate startDate, LocalDate endDate);

    /**
     * 승인 취소가 지우는 휴가 근태 행 — 기간 안, 휴가 상태, <b>출근 시각 없음</b>. 세 조건이 전부 맞는 행만이라
     * 사람이 실제로 출근한 날은 어떤 경로로도 지워지지 않는다({@code AbsenceCancelledListener}).
     * 벌크 JPQL이다 — 행을 올려서 하나씩 지울 이유가 없고, 영속성 컨텍스트에 이 행들이 올라와 있지도 않다.
     */
    @Modifying
    @Query("""
            DELETE FROM Attendance a
            WHERE a.member.userId = :userId
              AND a.workDate BETWEEN :startDate AND :endDate
              AND a.status IN :statuses
              AND a.clockInTime IS NULL
            """)
    int deleteAbsenceRows(@Param("userId") String userId,
                          @Param("startDate") LocalDate startDate,
                          @Param("endDate") LocalDate endDate,
                          @Param("statuses") Collection<AttendanceStatus> statuses);

    /**
     * 자정 결근 배치용. 해당 날짜에 기록이 <b>있는</b> 사번 목록을 가져온다.
     * 전체 인원에서 이 목록을 빼면 결근 대상이 된다.
     *
     * <p>Attendance 엔티티가 아니라 사번만 투영하는 이유: 5,000명 규모에서 엔티티를 전부
     * 적재할 이유가 없고, 필요한 것은 "기록이 있는가"뿐이다.
     */
    @Query("SELECT a.member.userId FROM Attendance a WHERE a.workDate = :workDate")
    List<String> findUserIdsByWorkDate(@Param("workDate") LocalDate workDate);

    /**
     * 관리자 대시보드의 하루 인원 집계 (P2-17-4). 쿼리 하나다.
     *
     * <p>{@code Member}에서 출발해 그날의 {@code Attendance}를 <b>왼쪽 조인</b>한다 — 근태 행이 없는 사람도
     * {@code headcount}에 들어가야 "150명 중 124명 출근"이 되기 때문이다. {@code Attendance}에서 출발하면
     * 안 찍은 사람이 사라지고, 인원을 따로 세면 쿼리가 둘이 된다.
     * 조인 조건의 {@code (user_id, work_date)}는 {@code uk_attendance_user_workdate}가 그대로 받는다.
     *
     * <p>{@code workShift}는 선택 필터. null 바인딩은 {@code WorkLogRepository}의 {@code status}와 같은 이유로
     * {@code CAST(... AS String)}.
     *
     * <p>결과는 한 행 — {@code [headcount, clockedIn, clockedOut, late, vacation, sick, absent]}. 반환형이 {@code List<Object[]>}인
     * 이유: {@code Object[]}로 선언하면 Spring Data가 그 행을 다시 배열로 감싸 {@code Object[]{Object[]}}가 온다(실측,
     * ClassCastException). 집계는 GROUP BY가 없어 항상 정확히 한 행이다.
     * {@code SUM(CASE ...)}은 조인 상대가 없으면 NULL이 아니라 0이 되도록 ELSE 0을 뒀고, 구역에 사람이 없으면
     * {@code COUNT}가 0이고 {@code SUM}들은 NULL이다 — 서비스가 0으로 받는다.
     */
    @Query("""
            SELECT COUNT(m),
                   SUM(CASE WHEN a.clockInTime IS NOT NULL THEN 1 ELSE 0 END),
                   SUM(CASE WHEN a.clockOutTime IS NOT NULL THEN 1 ELSE 0 END),
                   SUM(CASE WHEN a.status = com.DOCKin.attendance.model.AttendanceStatus.LATE THEN 1 ELSE 0 END),
                   SUM(CASE WHEN a.status = com.DOCKin.attendance.model.AttendanceStatus.VACATION THEN 1 ELSE 0 END),
                   SUM(CASE WHEN a.status = com.DOCKin.attendance.model.AttendanceStatus.SICK THEN 1 ELSE 0 END),
                   SUM(CASE WHEN a.status = com.DOCKin.attendance.model.AttendanceStatus.ABSENT THEN 1 ELSE 0 END)
            FROM Member m
            LEFT JOIN Attendance a ON a.member = m AND a.workDate = :workDate
            WHERE m.shipYardArea = :shipYardArea
              AND (CAST(:workShift AS String) IS NULL OR m.workShift = :workShift)
            """)
    List<Object[]> summarizeByAreaAndDate(@Param("shipYardArea") String shipYardArea,
                                          @Param("workShift") WorkShift workShift,
                                          @Param("workDate") LocalDate workDate);
}
