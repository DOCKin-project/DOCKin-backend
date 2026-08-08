package com.DOCKin.worklog.controller;

import com.DOCKin.ai.dto.SttDomain;
import com.DOCKin.ai.service.SttService;
import com.DOCKin.worklog.dto.WorkLogsCreateRequestDto;
import com.DOCKin.worklog.dto.WorkLogsUpdateRequestDto;
import com.DOCKin.worklog.dto.WorkLogDto;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.worklog.service.WorkLogsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

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
                                                       @RequestHeader(HttpHeaders.AUTHORIZATION) String token,
                                                       @RequestPart(value="images",required = false) List<MultipartFile> images
    ){
        String userId = customUserDetails.getMember().getUserId();
      WorkLogDto response =  workLogsService.createSttWorklog(userId,requestDto,file,token,images);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * <b>{@code sort}를 비워두면 정렬이 만들어지지 않는다.</b>
     *
     * <p>원래 {@code @PageableDefault(size = 20, direction = DESC)}였다. {@code direction}은
     * 정렬 <b>속성이 있을 때</b> 그 방향을 정하는 값이라, 속성이 없으면 방향만으로는 아무것도
     * 만들어지지 않는다 — 이 Pageable은 <b>unsorted</b>였고 응답 순서가 정해져 있지 않았다.
     *
     * <p>정렬 없는 OFFSET 페이징은 <b>페이지 경계에서 행이 중복되거나 누락된다.</b>
     * DB가 순서를 보장하지 않으므로 1페이지와 2페이지가 같은 행을 담을 수도, 어떤 행도
     * 담지 않을 수도 있다. {@code WorkLogRepository.findForIndexingAfter}가 커서 방식을
     * 고르며 적어 둔 이유가 정확히 이것인데, <b>목록 API는 그 지적을 받지 않은 채 남아 있었다.</b>
     *
     * <h4>{@code createdAt} 하나로는 부족하다</h4>
     * {@code created_at}은 유니크하지 않다. 같은 시각의 행이 둘 이상이면 그들 사이의 순서가
     * 다시 정해지지 않아 <b>같은 중복·누락이 그 경계에서 재현된다.</b> PK인 {@code logId}를
     * 뒤에 붙여 <b>전순서</b>로 만든다 — 같은 판단이 {@code findForIndexingAfter}에도 있다
     * ("정렬 키가 유니크(PK)라 중복·누락도 발생하지 않는다").
     */
    @Operation(summary="전체 작업일지 조회",description = "전체 작업자의 작업일지를 조회해줌")
    @GetMapping
    public ResponseEntity<Page<WorkLogDto>> getWorkLog(
            @AuthenticationPrincipal CustomUserDetails customUserDetails,
            @PageableDefault(size = 20, sort = {"createdAt", "logId"}, direction = Sort.Direction.DESC)Pageable pageable
            ){
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(workLogsService.readWorklog(userId,pageable));
    }

    @Operation(summary="특정 작업자 작업일지 조회",description = "특정 작업자의 작업일지를 조회해줌")
    @GetMapping("/others/{targetUserId}")
    public ResponseEntity<Page<WorkLogDto>> getMyWorkLog(@PathVariable String targetUserId,
                                                           @AuthenticationPrincipal CustomUserDetails customUserDetails,
                                                           @PageableDefault(size = 20, sort = {"createdAt", "logId"}, direction = Sort.Direction.DESC)Pageable pageable){
        String userId = customUserDetails.getMember().getUserId();
        return ResponseEntity.ok(workLogsService.readOtherWorklog(userId,targetUserId,pageable));
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

    @Operation(summary = "키워드로 게시물 검색", description = "키워드로 게시물 검색이 가능함")
    @GetMapping("/search")
    // 여기만 원래 sort가 있었다. logId를 더한 것은 createdAt이 유니크하지 않기 때문이다 -- 위 참고.
    public ResponseEntity<Page<WorkLogDto>> searchByKeyword( @PageableDefault(size = 20, sort = {"createdAt", "logId"}, direction = Sort.Direction.DESC)Pageable pageable,
                                                              String keyword){
        Page<WorkLogDto> workLogsDtos = workLogsService.searchByKeyword(keyword,pageable);
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
