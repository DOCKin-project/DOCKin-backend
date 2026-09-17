package com.DOCKin.attendance.service;

import com.DOCKin.attendance.dto.AttendanceDailySummaryDto;
import com.DOCKin.attendance.dto.AttendanceDto;
import com.DOCKin.attendance.dto.ClockInRequestDto;
import com.DOCKin.attendance.dto.ClockOutRequestDto;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.attendance.model.Attendance;
import com.DOCKin.attendance.model.AttendanceStatus;
import com.DOCKin.member.model.Member;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.member.model.WorkShift;
import com.DOCKin.attendance.repository.AttendanceRepository;
import com.DOCKin.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.DOCKin.attendance.dto.AttendanceDto.fromEntity;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceService {
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final long LOCK_LEASE_SECONDS = 3L;

    private final AttendanceRepository attendanceRepository;
    private final MemberRepository memberRepository;
    private final RedissonClient redissonClient;
    private final Clock clock;


    //출근로직
    //ADR-0001: 락 대기시간이 DB 트랜잭션에 포함되지 않도록, 이 메서드는 트랜잭션에 참여하지 않는다.
    //클래스 레벨 @Transactional(readOnly = true)를 그냥 상속받으면 INSERT가 읽기 전용 커넥션에서 막히므로 NOT_SUPPORTED로 명시한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AttendanceDto clockin(String userId, ClockInRequestDto dto) {
        LocalDate today = LocalDate.now(clock);
        String lockKey = "attendance:lock:" + userId + ":" + today;
        RLock lock = redissonClient.getLock(lockKey);

        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 1차 방어선(Redis) 사용 불가 - 2차 방어선(DB 유니크 제약)에 위임하고 진행한다.
            log.warn("Redis 분산락을 사용할 수 없어 DB 제약으로 폴백합니다. userId={}, cause={}", userId, e.toString());
            return doClockIn(userId, today, dto);
        }

        if (!locked) {
            // 동시 요청이 이미 같은 유저/날짜로 처리 중인 상태
            throw new BusinessException(ErrorCode.ATTENDANCE_ALREADY_CHECKED);
        }

        try {
            return doClockIn(userId, today, dto);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // Spring AOP는 같은 클래스 내부 호출(this.doClockIn())에는 프록시를 적용하지 못해 @Transactional이 무시된다.
    // 대신 조회/저장은 Spring Data JPA 리포지토리 자체의 트랜잭션 경계를 사용하고,
    // 두 호출 사이의 원자성은 Redis 락(1차)과 DB 유니크 제약(2차, uk_attendance_user_workdate)이 보장하므로
    // 별도의 트랜잭션 묶음이 필요하지 않다. (ADR-0001 참고)
    private AttendanceDto doClockIn(String userId, LocalDate today, ClockInRequestDto dto) {
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (attendanceRepository.findByMemberAndWorkDate(member, today).isPresent()) {
            throw new BusinessException(ErrorCode.ATTENDANCE_ALREADY_CHECKED);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 교대(work_shift)별 시작 시각 기준으로 지각을 판단한다. 필드 도입 이전 데이터 등으로 null이면 MORNING으로 간주.
        WorkShift shift = member.getWorkShift() != null ? member.getWorkShift() : WorkShift.MORNING;
        AttendanceStatus status = now.toLocalTime().isAfter(shift.getStartTime())
                ? AttendanceStatus.LATE
                : AttendanceStatus.NORMAL;

        Attendance attendance = Attendance.builder()
                .member(member)
                .clockInTime(now)
                .workDate(today)
                .status(status)
                .inLocation(dto.getInLocation())
                .build();

        try {
            Attendance saved = attendanceRepository.save(attendance);
            return fromEntity(saved);
        } catch (DataIntegrityViolationException e) {
            // 2차 방어선: 유니크 제약 위반 = 이미 출근 처리됨 (Redis 락 미사용/실패 경로에서 도달 가능)
            throw new BusinessException(ErrorCode.ATTENDANCE_ALREADY_CHECKED);
        }
    }

    //퇴근로직
    @Transactional
    public AttendanceDto clockout(String userId, ClockOutRequestDto dto){
        //멤버 존재 확인
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()-> new BusinessException(ErrorCode.USER_NOT_FOUND));

        //가장 마지막의 출근 기록을 가져옴
        LocalDate today = LocalDate.now(clock);
        Attendance attendance = attendanceRepository.findByMemberAndWorkDate(member,today)
                .orElseThrow(()->new BusinessException(ErrorCode.ATTENDANCE_NOT_CHECKED_IN));

        if(attendance.getClockOutTime()!=null){
            throw new BusinessException(ErrorCode.ATTENDANCE_ALREADY_CHECKED_OUT);
        }

        //시간 갱신
        LocalDateTime now = LocalDateTime.now(clock);
        attendance.setClockOutTime(now);
        attendance.setOutLocation(dto.getOutLocation());

        java.time.Duration duration = java.time.Duration.between(attendance.getClockInTime(),now);
        long h = duration.toHours();
        long m = duration.toMinutesPart();
        long s = duration.toSecondsPart();

        String timeString = String.format("%02d:%02d:%02d", h, m, s);
        attendance.setTotalWorkTime(timeString);

        return fromEntity(attendance);
    }

    /** {@code from}·{@code to}를 둘 다 안 주면 오늘까지 이 일수. 앱의 "최근 한 달" 화면 기준이다. */
    static final int DEFAULT_RANGE_DAYS = 31;
    /** 한 요청이 돌려줄 수 있는 최대 기간. 없으면 {@code from=2000-01-01}로 상한이 다시 사라진다. */
    static final int MAX_RANGE_DAYS = 366;

    /**
     * 개인 근태 조회 — 기간으로 (P2-20-6).
     *
     * <p>상한 없이 전부 주던 것을 바꿨다. 1인 1일 1행이라 당장은 작지만 자라기만 하고, 앱은 어차피
     * 월 단위로 본다. {@code to}가 없으면 오늘, {@code from}이 없으면 {@code to}에서
     * {@link #DEFAULT_RANGE_DAYS} 전. 둘 다 없으면 "오늘까지 최근 한 달"이다. 날짜는 {@link Clock} 기준 —
     * 출퇴근 판정과 같은 시계를 쓴다.
     *
     * <p>{@code from > to}는 400({@code INVALID_DATE_RANGE}), {@link #MAX_RANGE_DAYS}를 넘으면
     * 400({@code ATTENDANCE_RANGE_TOO_LONG}). 조용히 잘라 주지 않는다 — 잘렸는지 클라이언트가 알 수 없다.
     */
    @Transactional(readOnly = true)
    public List<AttendanceDto> getMyAttendanceRecords(String userId, LocalDate from, LocalDate to){
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1);
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.ATTENDANCE_RANGE_TOO_LONG);
        }

        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()-> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<Attendance> records = attendanceRepository
                .findByMemberAndWorkDateBetweenOrderByWorkDateDesc(member, start, end);

        return records.stream()
                .map(AttendanceDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 관리자 대시보드의 하루 인원 집계 (P2-17-4). {@code date} 생략은 오늘, {@code shipYardArea} 생략은 관리자 자신의 구역,
     * {@code workShift} 생략은 전 근무조.
     *
     * <p>다른 구역을 볼 수 있다 — 휴가 승인이 관리자의 구역을 보지 않는 것과 같다. 미래 날짜도 막지 않는다:
     * 휴가 승인이 그 기간의 {@code VACATION} 행을 미리 만들므로(P2-1) 내일의 휴가 인원은 이미 의미가 있다.
     * ADMIN 검사는 {@code /api/*}{@code /admin/**} 경로 규칙과 여기, 두 겹(P2-18-6).
     */
    @Transactional(readOnly = true)
    public AttendanceDailySummaryDto getDailySummary(String adminUserId, LocalDate date, String shipYardArea,
                                                     WorkShift workShift) {
        Member admin = memberRepository.findByUserId(adminUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (admin.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        LocalDate day = date != null ? date : LocalDate.now(clock);
        String area = shipYardArea != null ? shipYardArea : admin.getShipYardArea();

        Object[] row = attendanceRepository.summarizeByAreaAndDate(area, workShift, day).get(0);
        return new AttendanceDailySummaryDto(day, area, workShift,
                count(row[0]), count(row[1]), count(row[2]), count(row[3]), count(row[4]), count(row[5]), count(row[6]));
    }

    /** 집계 컬럼은 {@code Long}이고, 구역에 사람이 없으면 SUM이 NULL이다. */
    private static long count(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }
}
