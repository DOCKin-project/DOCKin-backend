package com.DOCKin.absence.controller;

import com.DOCKin.absence.dto.AbsenceDecisionRequestDto;
import com.DOCKin.absence.dto.AbsenceRequestCreateRequestDto;
import com.DOCKin.absence.dto.AbsenceRequestResponseDto;
import com.DOCKin.absence.service.AbsenceRequestService;
import com.DOCKin.global.security.auth.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "휴가 신청", description = "근무자의 연차/병가 신청 담당 api")
@Slf4j
@RestController
@RequestMapping("/api/absence")
@RequiredArgsConstructor
public class AbsenceRequestController {

    private final AbsenceRequestService absenceRequestService;

    @Operation(summary = "휴가 신청", description = "연차/병가를 신청함 (증빙서류 첨부 가능)")
    @PostMapping(value = "/requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AbsenceRequestResponseDto> createRequest(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @Valid @RequestPart(value = "requestDto") AbsenceRequestCreateRequestDto requestDto,
            @RequestPart(value = "document", required = false) MultipartFile document) {
        String userId = customUserDetails.getMember().getUserId();
        AbsenceRequestResponseDto response = absenceRequestService.createRequest(userId, requestDto, document);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "휴가 신청 취소", description = "대기 중(PENDING)인 내 신청을 취소함. 승인·거절·취소된 건은 409. 사유는 선택")
    @PatchMapping("/requests/{requestId}/cancel")
    public ResponseEntity<AbsenceRequestResponseDto> cancelRequest(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PathVariable Integer requestId,
            @RequestBody(required = false) AbsenceDecisionRequestDto dto) {
        String userId = customUserDetails.getMember().getUserId();
        String comment = dto != null ? dto.getComment() : null;
        return ResponseEntity.ok(absenceRequestService.cancelRequest(userId, requestId, comment));
    }

    @Operation(summary = "내 휴가 신청 목록 조회", description = "본인이 신청한 휴가 목록을 조회함")
    @GetMapping("/requests")
    public ResponseEntity<Page<AbsenceRequestResponseDto>> getMyRequests(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PageableDefault(size = 20, sort = "requestId", direction = Sort.Direction.DESC) Pageable pageable) {
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(absenceRequestService.getMyRequests(userId, pageable));
    }
}
