package com.DOCKin.ai.service;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.ai.model.ChatLog;
import com.DOCKin.ai.repository.ChatLogRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class chatBotService {
    private final ChatLogRepository chatLogRepository;
    private final WebClient fastApiWebClient;

@Transactional
    public Mono<ChatDomain.Response> chatBotFromSpringToFastApi(ChatDomain.Request request,String userId){

       return fastApiWebClient.post()
               .uri("/api/chatbot")
               .bodyValue(request)
               .retrieve()
               .onStatus(status->status.isError(),clientResponse ->
                       Mono.error(new BusinessException(ErrorCode.CHATBOT_NOT_WORK)))
               .bodyToMono(ChatDomain.Response.Result.class)
               .flatMap(apiResult-> {
                   ChatLog log = ChatLog.builder()
                           .traceId(request.traceId())
                           .userId(userId)
                           .userQuery(request.messages().get(0).content())
                           .reply(apiResult.reply())
                           .build();
                   chatLogRepository.save(log);
                   return Mono.just(new ChatDomain.Response(request.traceId(),apiResult));
               });
}
}
