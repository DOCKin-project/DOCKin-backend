package com.DOCKin.controller;

import com.DOCKin.dto.SafetyCourse.SafetyCourseResponseDto;
import com.DOCKin.service.SafetyCourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name="사용자 안전교육",description = "사용자 안전교육 담당 api")
@Slf4j
@RestController
@RequestMapping("/api/safety/user")
@RequiredArgsConstructor
public class SafetyUserController {

    private final SafetyCourseService safetyCourseService;

    @Operation(summary="미이수 영상 조회", description = "사용자의 미이수 영상을 조회해줌")
    @GetMapping("/training/uncompleted")
    public ResponseEntity<List<SafetyCourseResponseDto>> getUncompletedVideos() {
        return ResponseEntity.ok(null);
    }

    @Operation(summary="영상 조회 완료", description="사용자가 영상 조회를 완료했는지 확인해줌")
    @PostMapping("/training/complete/{videoId}")
    public ResponseEntity<Void> completeCourse(@PathVariable("videoId") Long videoId) {
        return ResponseEntity.noContent().build();
    }

    @Operation(summary="전체 교육 자료 조회",description = "전체 교육 자료를 조회할 수 있음")
    @GetMapping("/courses")
    public ResponseEntity<Page<SafetyCourseResponseDto>> getAllCourses(@PageableDefault(size = 10,
            sort = "courseId",direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(safetyCourseService.readSafetyCourse(pageable));
    }

    @Operation(summary="특정 작성자가 쓴 교육 상세 조회",description = "특정 작성자가 쓴 교육 자료를 조회할 수 있음")
    @GetMapping("/courses/user/{userId}")
    public ResponseEntity<Page<SafetyCourseResponseDto>> getCourseDetail(
            @PathVariable String userId,
            @PageableDefault(size = 10,
                    sort = "courseId",
                    direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(safetyCourseService.searchOtherSafetyCourse(userId,pageable));
    }

    @Operation(summary = "키워드로 검색하기",description = "키워드로 제목이나 내용을 검색할 수 있음")
    @GetMapping("/courses/search")
    public ResponseEntity<Page<SafetyCourseResponseDto>> searchByKeyword(String keyword,
                                                                         @PageableDefault(size= 10,
                                                                                 sort="courseId",
                                                                                 direction= Sort.Direction.DESC) Pageable pageable){
        return ResponseEntity.ok(safetyCourseService.searchSafetyCourse(keyword,pageable));

    }
}
