package com.DOCKin.safetyCourse.controller;

import com.DOCKin.safetyCourse.dto.SafetyCourseCreateRequestDto;
import com.DOCKin.safetyCourse.dto.SafetyCourseResponseDto;
import com.DOCKin.safetyCourse.dto.SafetyCourseUpdateRequestDto;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.safetyCourse.service.SafetyCourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


/**
 * 쓰기만 있다 — 등록·수정·삭제. 읽기(목록·작성자별·검색)는 {@code SafetyUserController}의
 * {@code /api/safety/user/courses…} 하나뿐이고 관리자도 그걸 쓴다.
 *
 * <p>2026-09-17까지는 같은 읽기 셋이 여기에도 똑같이 있었다(P2-20-5). 서비스 메서드까지 같은 것을
 * 부르는 완전한 복제라 경로만 여섯이었고, 둘 중 하나만 고치는 실수가 나는 자리였다 —
 * P2-18-6이 잡은 "관리자 읽기 셋이 일반 사용자에게 열려 있었다"가 정확히 그 자리다.
 * {@code /api/*}{@code /admin/**}는 ADMIN만 통과하므로 관리자가 user 경로를 쓰는 데는 아무 제약이 없다.
 */
@Tag(name="관리자용 안전교육 관리", description="안전교육 등록·수정·삭제. 조회는 /api/safety/user/courses")
@Slf4j
@RestController
@RequestMapping("/api/safety/admin")
@RequiredArgsConstructor
public class SafetyAdminController {

    private final SafetyCourseService safetyCourseService;

    @Operation(summary="교육 자료 등록",description = "교육 자료를 등록할 수 있음")
    @PostMapping("/courses")
    public ResponseEntity<SafetyCourseResponseDto> createCourse(@Valid @RequestBody SafetyCourseCreateRequestDto dto,
                                                                @AuthenticationPrincipal CustomUserDetails customUserDetails) {
        String creatorId = customUserDetails.getMember().getUserId();
        SafetyCourseResponseDto safetyCourse = safetyCourseService.createSafetyCourseResponse(dto,creatorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(safetyCourse);
    }

    @Operation(summary="교육 자료 수정",description = "특정 교육 자료를 수정할 수 있음")
    @PutMapping("/courses/{courseId}")
    public ResponseEntity<SafetyCourseResponseDto> updateCourse(@PathVariable Integer courseId,
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
                                                               @Valid @RequestBody SafetyCourseUpdateRequestDto dto) {
        String userId = customUserDetails.getMember().getUserId();
        SafetyCourseResponseDto safetyCourse = safetyCourseService.reviseSafetyCourse(dto,userId,courseId);
        return ResponseEntity.ok(safetyCourse);
    }

    @Operation(summary="특정 교육 자료 삭제",description = "특정 교육자료를 삭제할 수 있음")
    @DeleteMapping("/courses/{courseId}")
    public ResponseEntity<Void> deleteCourse(@AuthenticationPrincipal CustomUserDetails customUserDetails,
                                             @PathVariable Integer courseId) {
        String userId = customUserDetails.getMember().getUserId();
        safetyCourseService.deleteSafetyCourse(userId,courseId);
        return ResponseEntity.noContent().build();
    }


}
