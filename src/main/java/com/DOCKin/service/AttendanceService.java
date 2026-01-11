package com.DOCKin.service;

import com.DOCKin.dto.Attendance.AttendanceDto;
import com.DOCKin.dto.Attendance.ClockInRequestDto;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.model.Member.Member;
import com.DOCKin.repository.AttendanceRepository;
import com.DOCKin.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceService {
    private final AttendanceRepository attendanceRepository;
    private final MemberRepository memberRepository;

    //출근로직
    @Transactional
    public AttendanceDto clockin(String userId, ClockInRequestDto dto){
        // 멤버 존재 확인
        Member member = memberRepository.findByUserId(userId)
                .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        String status= ""
    }
}
