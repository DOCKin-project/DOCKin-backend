package com.DOCKin.rag.generation;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.ai.service.FastApiService;
import com.DOCKin.rag.dto.RetrievalResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 기본 생성기. 팀원 FastAPI {@code /api/chatbot}에 그대로 넘긴다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.chatbot.stub", havingValue = "false", matchIfMissing = true)
public class FastApiChatGenerator implements ChatGenerator {

    private final FastApiService fastApiService;

    @Override
    public ChatDomain.Response.Result generate(ChatDomain.Request augmented, RetrievalResult retrieval, String userId) {
        // 컨트롤러에서 넘어온 인증 컨텍스트를 유지하기 위해 현재 스레드에서 block한다.
        return fastApiService.chatBotFromSpringToFastApi(augmented, userId).block().result();
    }
}
