package com.DOCKin.ai.controller;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.ai.dto.TranslateDomain;
import com.DOCKin.ai.dto.OnlineTranslateDomain;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.logging.TraceId;
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
        // translate_logs.trace_id에 저장되고 FastAPI로도 넘어가는 값이다. 로그도 같은 값을 써야
        // DB와 로그를 이을 수 있다(P2-11-3).
        //
        // 다만 이 경로는 Mono를 반환하므로 여기까지다 -- WebClient 호출 이후는 Reactor
        // 스레드에서 이어지고 MDC는 ThreadLocal이라 따라가지 않는다. TraceIdFilter 주석 참고.
        TraceId.override(traceId);

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

        // chat_history.trace_id에 저장되는 값과 로그를 같은 ID로 묶는다(P2-11-3).
        // 이 경로는 동기라 아래 RagChatService·FastApiService의 로그까지 전부 이어진다.
        TraceId.override(request.traceId());

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

        // work_log_translations.trace_id와 로그를 잇는다(P2-11-3).
        TraceId.override(request.traceId());

        String userId = customUserDetails.getMember().getUserId();

        TranslateDomain.Response response = fastApiService.saveTranslateLog(logId,request,userId);

        return response;
    }
}