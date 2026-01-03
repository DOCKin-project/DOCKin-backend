package com.DOCKin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/safety/admin")
@RequiredArgsConstructor
public class SafetyAdminController {

    //전체 교육 자료 조회 api
    @GetMapping("/enrollments")
    public ResponseEntity<List<?>> getAllEnrollments(){
        return ResponseEntity.ok(null);
    }
    //교육 자료 등록 api
    @PostMapping("/courses")
    public ResponseEntity<?> createCourse() {
        return ResponseEntity.ok().build();
    }

    //전체 교육 자료 조회 api
    @GetMapping("/courses")
    public ResponseEntity<List<?>> getAllCourses() {
        return ResponseEntity.ok().build();
    }

    //특정 교육 상세 조회 api
    @GetMapping("/courses/{courseId}")
    public ResponseEntity<?> getCourseDetail() {
        return ResponseEntity.ok().build();
    }

    //교육 자료 수정 api
    @PutMapping("/courses/{courseId}")
    public ResponseEntity<?> updateCourse() {
        return ResponseEntity.ok().build();
    }

    //교육 자료 삭제 api
    @DeleteMapping("/courses/{courseId}")
    public ResponseEntity<Void> deleteCourse() {
        return ResponseEntity.noContent().build();
    }

}
