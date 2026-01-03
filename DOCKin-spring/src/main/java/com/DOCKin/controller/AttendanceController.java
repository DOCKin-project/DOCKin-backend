package com.DOCKin.controller;

import com.DOCKin.dto.AttendanceDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/attendance")
public class AttendanceController {
    //Clockin(출근 API)
    @PostMapping("/in")
    public ResponseEntity<AttendanceDto>ClockIn(){
        return ResponseEntity.ok().build();
    }

    //Clockout(퇴근 API)
    @PostMapping("/out")
    public ResponseEntity<AttendanceDto>ClockOut(){
        return ResponseEntity.ok().build();
    }

    //개인 근태 기록 조회 API
    @GetMapping
    public ResponseEntity<List<AttendanceDto>>GetMyAttendanceRecords(){
        return ResponseEntity.ok(null);
    }
}
