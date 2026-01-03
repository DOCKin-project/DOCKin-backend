package com.DOCKin.controller;

import com.DOCKin.dto.Work_logsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/work-logs")
public class WorkLogsController {
    //특정 작업자 작업일지 생성 api
    @PostMapping("/{logId}")
    public ResponseEntity<Work_logsDto> createWorkLog(){
        return ResponseEntity.status(HttpStatus.CREATED).body(null);
    }

    //전체 작업일지 조회 api
    @GetMapping
    public ResponseEntity<List<Work_logsDto>> getWorkLog(){
        return ResponseEntity.status(HttpStatus.CREATED).body(null);
    }

    //특정 작업자 작업일지 조회 api
    @GetMapping("/{logId}")
    public ResponseEntity<Work_logsDto> getMyWorkLog(){
        return ResponseEntity.status(HttpStatus.CREATED).body(null);
    }

    //특정 작업자 작업일지 수정 api
    @PutMapping("/{logId}")
    public ResponseEntity<Work_logsDto> PutMyWorkLog(){
        return ResponseEntity.status(HttpStatus.CREATED).body(null);
    }

    //특정 작업자 작업일지 삭제 api
    @DeleteMapping("/{logId}")
    public ResponseEntity<Work_logsDto> DeleteMyWorkLog(){
        return ResponseEntity.status(HttpStatus.CREATED).body(null);
    }
}
