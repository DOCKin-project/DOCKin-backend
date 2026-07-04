package com.DOCKin.attendance.service;

import com.DOCKin.attendance.dto.AttendanceDto;
import com.DOCKin.attendance.dto.ClockInRequestDto;
import com.DOCKin.attendance.dto.ClockOutRequestDto;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.attendance.model.Attendance;
import com.DOCKin.attendance.model.AttendanceStatus;
import com.DOCKin.member.model.Member;
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


    //출근로직
    //ADR-0001: 락 대기시간이 DB 트랜잭션에 포함되지 않도록, 이 메서드는 트랜잭션에 참여하지 않는다.
    //클래스 레벨 @Transactional(readOnly = true)를 그냥 상속받으면 INSERT가 읽기 전용 커넥션에서 막히므로 NOT_SUPPORTED로 명시한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AttendanceDto clockin(String userId, ClockInRequestDto dto) {
        LocalDate today = LocalDate.now();
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

        LocalDateTime now = LocalDateTime.now();
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
        LocalDate today = LocalDate.now();
        Attendance attendance = attendanceRepository.findByMemberAndWorkDate(member,today)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

        if(attendance.getClockOutTime()!=null){
            throw new IllegalArgumentException("출근 기록이 존재하지 않습니다.");
        }

        //시간 갱신
        LocalDateTime now = LocalDateTime.now();
        attendance.setClockOutTime(LocalDateTime.now());
        attendance.setOutLocation(dto.getOutLocation());

        java.time.Duration duration = java.time.Duration.between(attendance.getClockInTime(),now);
        long h = duration.toHours();
        long m = duration.toMinutesPart();
        long s = duration.toSecondsPart();

        String timeString = String.format("%02d:%02d:%02d", h, m, s);
        attendance.setTotalWorkTime(timeString);

        return fromEntity(attendance);
    }

    //개인 출퇴 기록 조회
    @Transactional(readOnly = true)
    public List<AttendanceDto> getMyAttendanceRecords(String userId){
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()-> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<Attendance> records = attendanceRepository.findByMemberOrderByWorkDateDesc(member);

        return records.stream()
                .map(AttendanceDto::fromEntity)
                .collect(Collectors.toList());
    }
}
