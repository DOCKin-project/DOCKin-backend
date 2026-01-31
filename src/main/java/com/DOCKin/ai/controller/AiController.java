package com.DOCKin.ai.controller;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.ai.service.chatBotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Tag(name = "Fast api 통신용")
@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/api")
public class AiController {
    private final chatBotService chatBotService;

    @Operation(summary = "챗봇",description = "fast api에서 챗봇 json을 받는다")
    @PostMapping("/chatbot")
    public Mono<ChatDomain.Response> chatBot(@AuthenticationPrincipal CustomUserDetails customUserDetails,
                                             @Valid @RequestBody ChatDomain.Request request){
        String userId = customUserDetails.getMember().getUserId();
        return chatBotService.chatBotFromSpringToFastApi(request,userId);
    }




}
