package com.DOCKin.attendance.model;

import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.WorkShift;
import jakarta.persistence.*;
import lombok.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;


@Entity
@Table(name="attendance", uniqueConstraints = @UniqueConstraint(
        name = "uk_attendance_user_workdate", columnNames = {"user_id", "work_date"}))
@Builder
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor(access= AccessLevel.PROTECTED)
public class Attendance {
    /**
     * PK.
     *
     * <p>{@code SEQUENCE}인 이유는 {@code DocumentChunk}와 같다 — 결근 배치와 휴가 반영이
     * 인원 수만큼 한 번에 INSERT하는데, IDENTITY는 JDBC 배치를 막는다.
     * 5,000명 규모에서 결근 처리 한 번이 INSERT 왕복 5,000번이 되는 것을 피한다.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "attendance_seq")
    @SequenceGenerator(name = "attendance_seq", sequenceName = "attendance_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch =FetchType.LAZY)
    @JoinColumn(name="user_id",nullable=false)
    private Member member;

    /**
     * 출근 시각.
     *
     * <p><b>nullable이다.</b> 원래 NOT NULL이었으나 그 설계는 "출근한 날"만 상정한 것이었다.
     * {@link AttendanceStatus#VACATION}/{@link AttendanceStatus#SICK}(승인된 휴가)와
     * {@link AttendanceStatus#ABSENT}(무단 결근)는 출근 자체가 없어 넣을 값이 없다.
     * 더미 시각(자정 등)을 넣으면 "0시에 출근한 기록"이 되어 근무시간 집계를 오염시키므로,
     * 없는 것은 없는 대로 둔다.
     */
    @Column(name="clock_in_time")
    private LocalDateTime clockInTime;

    @Column(name="clock_out_time")
    private LocalDateTime clockOutTime;

    /**
     * 근무일. <b>벽시계 날짜가 아니라 교대 기준일이다</b>({@link WorkDay}, ADR-0010). 야간조의 D일 22:00 출근과
     * D+1일 06:00 퇴근은 둘 다 근무일 D다. 예전에는 {@code LocalDate.now()}였고 그래서 야간조가 퇴근을 못 찍었다(#98).
     */
    @Column(name="work_date",nullable = false)
    private LocalDate workDate;

    /**
     * 이 행을 판정한 교대(V13). {@code users.work_shift}는 바뀔 수 있으므로 여기 스냅샷으로 남긴다 —
     * 석 달 전 지각 기록이 "어느 교대 기준으로 지각인가"에 답하려면 그때의 교대가 필요하다.
     * 관리자 집계(P2-17-4)가 교대별로 나눌 때도 사용자의 현재 교대가 아니라 이 컬럼을 봐야 한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "work_shift", length = 20, nullable = false)
    private WorkShift workShift;

    @Enumerated(EnumType.STRING) //NORMAL, LATE, ABSENT, VACATION, SICK
    @Column(name="status",length=20,nullable=false)
    private AttendanceStatus status;

    @Column(name="in_location")
    private String inLocation;

    @Column(name="out_location")
    private String outLocation;

    /**
     * 근무 시간(초). 퇴근 전에는 null. 예전 {@code total_work_time}은 {@code "HH:mm:ss"} 문자열이라 합계도 평균도
     * 낼 수 없었다(V13이 초로 바꿨다). 응답의 {@code totalWorkTime} 문자열은 {@code AttendanceDto}가 여기서 만든다.
     */
    @Column(name = "work_seconds")
    private Integer workSeconds;

    /**
     * 승인된 휴가/병가로 인한 근태 기록을 만든다.
     *
     * <p>휴가가 승인됐는데 근태에 반영되지 않으면 그날은 무단 결근 처리 대상이 된다.
     * {@code AbsenceApprovedEvent} 수신 시 이 팩터리로 기록을 남겨 자정 배치가 건드리지 않게 한다.
     */
    public static Attendance ofApprovedAbsence(Member member, LocalDate workDate, AttendanceStatus status) {
        return Attendance.builder()
                .member(member)
                .workShift(member.workShiftOrDefault())
                .workDate(workDate)
                .status(status)
                .build();
    }

    /** 자정 배치가 만드는 무단 결근 기록. 출퇴근 시각과 위치가 모두 없다. */
    public static Attendance ofAbsent(Member member, LocalDate workDate) {
        return Attendance.builder()
                .member(member)
                .workShift(member.workShiftOrDefault())
                .workDate(workDate)
                .status(AttendanceStatus.ABSENT)
                .build();
    }

    /**
     * 출근 기록. 근무일과 지각 여부는 {@link WorkDay}가 교대 기준으로 정한다 — 호출자가 날짜를 넘기지 않는다.
     * 넘기게 두면 {@code LocalDate.now()}를 넣는 호출자가 다시 생긴다.
     */
    public static Attendance clockIn(Member member, LocalDateTime now, String inLocation) {
        WorkShift shift = member.workShiftOrDefault();
        return Attendance.builder()
                .member(member)
                .workShift(shift)
                .workDate(WorkDay.of(shift, now))
                .clockInTime(now)
                .status(WorkDay.isLate(shift, now) ? AttendanceStatus.LATE : AttendanceStatus.NORMAL)
                .inLocation(inLocation)
                .build();
    }

    /** 퇴근. 근무 시간은 출퇴근 시각의 차를 초로 — 문자열로 만들어 저장하지 않는다. */
    public void clockOut(LocalDateTime now, String outLocation) {
        this.clockOutTime = now;
        this.outLocation = outLocation;
        this.workSeconds = (int) Duration.between(this.clockInTime, now).getSeconds();
    }

    /** 출근은 했고 퇴근은 아직인 기록인가. 휴가·결근 행은 출근 시각이 없어 여기 해당하지 않는다. */
    public boolean isOpen() {
        return clockInTime != null && clockOutTime == null;
    }

    /** 출근하지 않은 상태(휴가/병가/결근)인지. 근무시간 집계에서 제외할 때 쓴다. */
    public boolean isNonWorkingDay() {
        return status == AttendanceStatus.VACATION
                || status == AttendanceStatus.SICK
                || status == AttendanceStatus.ABSENT;
    }
}

