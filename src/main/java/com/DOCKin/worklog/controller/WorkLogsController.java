package com.DOCKin.worklog.controller;

import com.DOCKin.ai.dto.SttDomain;
import com.DOCKin.ai.service.SttService;
import com.DOCKin.worklog.dto.WorkLogCursor;
import com.DOCKin.worklog.dto.WorkLogsCreateRequestDto;
import com.DOCKin.worklog.dto.WorkLogsUpdateRequestDto;
import com.DOCKin.worklog.dto.WorkLogDto;
import com.DOCKin.worklog.model.WorkLogStatus;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.worklog.service.WorkLogsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;


@Tag(name="작업일지 CRUD",description = "작업일지 CRUD가 가능함")
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/work-logs")
public class WorkLogsController {
    private final WorkLogsService workLogsService;

    @Operation(summary="특정 작업자 작업일지 생성(사진 포함)",description = "특정 작업자의 작업일지를 생성해줌")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WorkLogDto> createWorkLog(@AuthenticationPrincipal CustomUserDetails customUserDetails,
                                                      @Valid @RequestPart(value="requestDto") WorkLogsCreateRequestDto requestDto,
                                                      @RequestPart(value="images", required=false)List<MultipartFile> images
                                                      ){
        String userId = customUserDetails.getMember().getUserId();
        WorkLogDto response =  workLogsService.createWorklog(userId,requestDto,images);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }


    @Operation(summary="Stt용 특정 작업자 작업일지 생성",description = "특정 작업자의 작업일지를 생성해줌")
    @PostMapping(value= "/stt",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WorkLogDto> createWorkLog( @AuthenticationPrincipal CustomUserDetails customUserDetails,
        @RequestPart(value="request") @Valid WorkLogsCreateRequestDto requestDto,
                                                       @RequestPart(value="file",required = false) MultipartFile file,
                                                       @RequestPart(value="images",required = false) List<MultipartFile> images
    ){
        String userId = customUserDetails.getMember().getUserId();
      WorkLogDto response =  workLogsService.createSttWorklog(userId,requestDto,file,images);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 목록 셋의 페이지 규칙 (DB-IMPROVEMENT-PLAN A4·D2, 2026-09-15).
     *
     * <p><b>순서는 서버가 정한다</b> — {@code createdAt DESC, logId DESC}, 리포지토리 쿼리에 박혀 있고
     * 서비스가 요청의 {@code sort}를 뗀다. 아래 {@code @PageableDefault(sort = ...)}는 그 계약을 적어 둔
     * 것이다(채팅 목록과 같은 구조, {@code PageableSortDefaultTest}가 선언을 검사한다). 2026-09-17부터는
     * {@code PageableConfig}가 요청의 {@code sort}를 아예 읽지 않으므로 이 애노테이션이 곧 컨트롤러가 받는
     * 정렬이기도 하다 — 다만 이 셋은 어차피 서비스가 sort를 떼고 커서를 쓰니 여기서는 문서의 역할이 크다.
     * 처음엔 {@code @PageableDefault(direction = DESC)}만 있어 정렬 <b>속성이 없는</b> unsorted Pageable이었고,
     * 정렬 없는 OFFSET 페이징은 페이지 경계에서 행이 겹치거나 빠졌다(P2-15-3). 그 뒤 {@code sort}를 넣었는데,
     * 클라이언트가 다른 sort를 넘기면 아래 커서와 어긋나므로 정렬을 쿼리로 옮기고 요청 값은 무시한다.
     * {@code created_at}은 유니크하지 않아 PK {@code logId}를 뒤에 붙여 전순서로 만든다 —
     * {@code findForIndexingAfter}와 같은 판단.
     *
     * <p><b>응답은 {@code Slice}다</b> — 전체 건수·전체 페이지 수가 없다. {@code Page}는 매 요청 COUNT를
     * 같이 던지는데 100만 행에서 인덱스가 있어도 12.8ms였고, MVCC라 LIMIT의 이득이 없다(P2-15-5 ②).
     * 다음 페이지가 있는지는 {@code last}로 본다. 채팅 목록과 같은 형태다.
     *
     * <p><b>다음 페이지는 커서로</b> — 마지막 원소의 {@code createdAt}·{@code logId}를
     * {@code beforeCreatedAt}·{@code beforeLogId}로 넘긴다. {@code page} 번호도 여전히 받지만
     * OFFSET이라 뒤로 갈수록 비싸진다(500페이지 150ms, P2-15-5 ③). 커서가 있으면 {@code page}는 무시한다.
     */
    @Operation(summary="전체 작업일지 조회",
            description = "같은 구역의 작업일지를 최신순으로. status(PENDING/APPROVED/REJECTED)를 주면 그 상태만 — "
                    + "관리자의 미승인 큐, 근로자의 반려 건이 이 필터다. 다음 페이지는 마지막 원소의 createdAt·logId를 "
                    + "beforeCreatedAt·beforeLogId로. 둘 다 없으면 첫 페이지. last=false면 다음이 있다")
    @GetMapping
    public ResponseEntity<Slice<WorkLogDto>> getWorkLog(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @RequestParam(required = false) WorkLogStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beforeCreatedAt,
            @RequestParam(required = false) Long beforeLogId,
            @PageableDefault(size = 20, sort = {"createdAt", "logId"}, direction = Sort.Direction.DESC) Pageable pageable
            ){
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(workLogsService.readWorklog(userId, status,
                WorkLogCursor.of(beforeCreatedAt, beforeLogId), pageable));
    }

    @Operation(summary="특정 작업자 작업일지 조회",description = "특정 작업자의 작업일지를 최신순으로. 페이지 규칙은 전체 조회와 같다")
    @GetMapping("/others/{targetUserId}")
    public ResponseEntity<Slice<WorkLogDto>> getMyWorkLog(@PathVariable String targetUserId,
                                                           @AuthenticationPrincipal CustomUserDetails customUserDetails,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beforeCreatedAt,
                                                           @RequestParam(required = false) Long beforeLogId,
                                                           @PageableDefault(size = 20, sort = {"createdAt", "logId"}, direction = Sort.Direction.DESC) Pageable pageable){
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(workLogsService.readOtherWorklog(userId,targetUserId,
                WorkLogCursor.of(beforeCreatedAt, beforeLogId), pageable));
    }

    @Operation(summary="특정 작업자 작업일지 수정",description = "특정 작업자의 작업일지를 수정해줌")
    @PutMapping("/{logId}")
    public ResponseEntity<WorkLogDto> PutMyWorkLog(@PathVariable Long logId,
                                                     @Valid @RequestPart(value = "requestDto") WorkLogsUpdateRequestDto request,
                                                     @AuthenticationPrincipal CustomUserDetails customUserDetails,
                                                     @RequestPart(value="images", required = false) List<MultipartFile> images){
        String userId = customUserDetails.getMember().getUserId();
        WorkLogDto response = workLogsService.updateWorklog(userId,logId,request,images);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @Operation(summary = "키워드로 게시물 검색", description = "같은 구역에서 키워드 검색, 최신순. 페이지 규칙은 전체 조회와 같다")
    @GetMapping("/search")
    public ResponseEntity<Slice<WorkLogDto>> searchByKeyword( @AuthenticationPrincipal CustomUserDetails customUserDetails,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beforeCreatedAt,
                                                              @RequestParam(required = false) Long beforeLogId,
                                                              @PageableDefault(size = 20, sort = {"createdAt", "logId"}, direction = Sort.Direction.DESC) Pageable pageable,
                                                              @RequestParam @NotBlank String keyword){
        String userId = customUserDetails.getMember().getUserId();
        Slice<WorkLogDto> workLogsDtos = workLogsService.searchByKeyword(userId, keyword,
                WorkLogCursor.of(beforeCreatedAt, beforeLogId), pageable);
        return ResponseEntity.ok(workLogsDtos);
    }

    @Operation(summary="특정 작업자 작업일지 삭제",description = "특정 작업자의 작업일지를 삭제해줌")
    @DeleteMapping("/{logId}")
    public ResponseEntity<Void> DeleteMyWorkLog(@PathVariable Long logId,
                                                @AuthenticationPrincipal CustomUserDetails customUserDetails){
        String userId = customUserDetails.getMember().getUserId();
        workLogsService.deleteWorklog(userId,logId);
        return ResponseEntity.noContent().build();
    }
}
