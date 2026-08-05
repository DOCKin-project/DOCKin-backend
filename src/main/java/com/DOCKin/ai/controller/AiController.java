package com.DOCKin.ai.controller;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.ai.dto.TranslateDomain;
import com.DOCKin.ai.dto.OnlineTranslateDomain;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.rag.service.RagChatService;
import com.DOCKin.ai.service.FastApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

@Tag(name = "Fast api 통신용")
@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final FastApiService fastApiService;
    private final RagChatService ragChatService;

    @Operation(summary= "stt 실시간 번역",description = "실시간 번역을 해준다")
    @PostMapping(value = "/rt-translate", consumes= MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<OnlineTranslateDomain.RtTranslateResponse>> rtTranslate(
            @RequestPart("file")MultipartFile file,
            @RequestPart("source") String source,
            @RequestPart("target") String target,
            @RequestPart("traceId") String traceId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String token
            ){
return fastApiService.realtimeTranslate(file, source, target, traceId, token)
        .map(response->ResponseEntity.ok(response));
    }

    @Operation(summary = "챗봇(RAG)",
            description = "작업일지·안전교육에서 근거를 검색해 프롬프트에 붙인 뒤 FastAPI 챗봇을 호출한다. "
                    + "검색은 사용자 권한으로 선필터되며, 임베딩 서버 장애 시 키워드 검색으로 폴백한다.")
    @PostMapping("/chatbot")
    public ChatDomain.Response chatBot(@AuthenticationPrincipal CustomUserDetails customUserDetails,
                                       @Valid @RequestBody ChatDomain.Request request) {

        if (customUserDetails == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);

        String userId = customUserDetails.getMember().getUserId();
        boolean admin = customUserDetails.getMember().getRole() == UserRole.ADMIN;

        // 근거 검색 → 프롬프트 조립 → FastAPI 호출 → 출처 기록까지 RagChatService가 담당한다.
        return ragChatService.chat(request, userId, admin);
    }

    @Operation(summary = "작업일지 번역", description = "fast api에서 작업일지 번역을 받는다")
    @PostMapping("/translate/{logId}")
    public TranslateDomain.Response translateWorklog(@AuthenticationPrincipal CustomUserDetails customUserDetails,
                                                     @Valid @RequestBody TranslateDomain.Request request,
                                                     @PathVariable Long logId) {

        if (customUserDetails == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        String userId = customUserDetails.getMember().getUserId();

        TranslateDomain.Response response = fastApiService.saveTranslateLog(logId,request,userId);

        return response;
    }
}