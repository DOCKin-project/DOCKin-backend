package com.DOCKin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/safety")
@RequiredArgsConstructor
public class SafetyUserController {

    //미이수 영상 조회 api
    @GetMapping("/training/uncompleted")
    public ResponseEntity<?> getUncompletedVideos() {
        return ResponseEntity.ok(null);
    }

    //영상 조회 완료 api
    @PostMapping("/training/complete/{videoId}")
    public ResponseEntity<Void> completeCourse() {
        return ResponseEntity.ok().build();
    }

    //전체 교육 조회api
    @GetMapping("/courses")
    public ResponseEntity<?> getAllCoursesForUser() {
        return ResponseEntity.ok(null);
    }

    //특정 교육 상세 조회api
    @GetMapping("/courses/{courseId}")
    public ResponseEntity<?> getCourseDetailForUser() {
        return ResponseEntity.ok(null);
    }
}
