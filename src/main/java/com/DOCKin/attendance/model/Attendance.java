package com.DOCKin.attendance.model;

import com.DOCKin.member.model.Member;
import jakarta.persistence.*;
import lombok.*;
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
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
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

    @Column(name="work_date",nullable = false)
    private LocalDate workDate;

    @Enumerated(EnumType.STRING) //NORMAL, LATE, ABSENT, VACATION, SICK
    @Column(name="status",length=20,nullable=false)
    private AttendanceStatus status;

    @Column(name="in_location")
    private String inLocation;

    @Column(name="out_location")
    private String outLocation;

    @Column(name="total_work_time")
    private String totalWorkTime;

    /**
     * 승인된 휴가/병가로 인한 근태 기록을 만든다.
     *
     * <p>휴가가 승인됐는데 근태에 반영되지 않으면 그날은 무단 결근 처리 대상이 된다.
     * {@code AbsenceApprovedEvent} 수신 시 이 팩터리로 기록을 남겨 자정 배치가 건드리지 않게 한다.
     */
    public static Attendance ofApprovedAbsence(Member member, LocalDate workDate, AttendanceStatus status) {
        return Attendance.builder()
                .member(member)
                .workDate(workDate)
                .status(status)
                .build();
    }

    /** 자정 배치가 만드는 무단 결근 기록. 출퇴근 시각과 위치가 모두 없다. */
    public static Attendance ofAbsent(Member member, LocalDate workDate) {
        return Attendance.builder()
                .member(member)
                .workDate(workDate)
                .status(AttendanceStatus.ABSENT)
                .build();
    }

    /** 출근하지 않은 상태(휴가/병가/결근)인지. 근무시간 집계에서 제외할 때 쓴다. */
    public boolean isNonWorkingDay() {
        return status == AttendanceStatus.VACATION
                || status == AttendanceStatus.SICK
                || status == AttendanceStatus.ABSENT;
    }
}

