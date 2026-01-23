package com.DOCKin.controller;

import com.DOCKin.dto.WorkLogs.comment.CommentCreateRequestDto;
import com.DOCKin.dto.WorkLogs.comment.CommentResponseDto;
import com.DOCKin.dto.WorkLogs.comment.CommentUpdateRequestDto;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.model.WorkLogs.Comment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name="작업일지 댓글 기능",description = "작업일지에 댓글을 달 수 있음")
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/work-logs/{logId}/comments")
public class WorkLogCommentController {

    @Operation(summary = "관리자 댓글 작성",description = "관리자가 근로자 작업일지 피드백을 해줌")
    @PostMapping
    public ResponseEntity<CommentResponseDto> postComment(@AuthenticationPrincipal CustomUserDetails customUserDetails
    , @Valid @RequestBody CommentCreateRequestDto dto, String logId){
        return ResponseEntity.ok(null);
    }

    @Operation(summary="관리자 댓글 수정",description = "관리자가 자기가 쓴 댓글을 수정함")
    @PutMapping
    public ResponseEntity<CommentResponseDto> putComment(@AuthenticationPrincipal CustomUserDetails customUserDetails,
                                                            @Valid @RequestBody CommentUpdateRequestDto dto,
                                                            @PathVariable Long logId){
        return ResponseEntity.ok(null);
    }

    @Operation(summary="관리자 댓글 조회",description = "관리자가 쓴 댓글을 볼 수 있음")
    @GetMapping
    public ResponseEntity<CommentResponseDto> getComment(@AuthenticationPrincipal CustomUserDetails customUserDetails){
        return ResponseEntity.ok(null);
    }

    @Operation(summary ="관리자 댓글 삭제",description = "관리자가 쓴 댓글을 삭제할 수 있음")
    @DeleteMapping
    public ResponseEntity<Void> deleteComment(@AuthenticationPrincipal CustomUserDetails customUserDetails,
                                                            String userId){
        return ResponseEntity.noContent().build();
    }
}
