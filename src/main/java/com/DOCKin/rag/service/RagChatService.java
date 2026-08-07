package com.DOCKin.rag.service;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.ai.service.FastApiService;
import com.DOCKin.rag.dto.RetrievalResult;
import com.DOCKin.rag.dto.RetrievedChunk;
import com.DOCKin.rag.model.SourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 오케스트레이션. 질문을 받아 근거를 찾고, 프롬프트를 조립해 챗봇을 호출하고, 출처를 남긴다.
 *
 * <p>RAG의 R(Retrieval)과 프롬프트 조립이 여기 있고, G(Generation)는 팀원 FastAPI가 담당한다.
 *
 * <h3>팀원 API 계약을 바꾸지 않는다</h3>
 * 근거를 별도 필드로 보내려면 FastAPI를 수정해야 하는데 그쪽은 담당 범위 밖이다.
 * 대신 <b>user 메시지 {@code content} 안에 근거를 녹여서 보낸다.</b>
 * 기존 {@code /api/chatbot} 요청 스펙을 한 글자도 바꾸지 않고 RAG를 붙일 수 있다.
 *
 * <h3>근거가 없어도 답변은 나간다</h3>
 * 검색 결과가 없거나 임베딩 서버가 죽어도 챗봇 자체는 동작해야 한다.
 * 그 경우 원본 질문을 그대로 전달하고 {@code retrieval_mode}에 상태를 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService {

    private final RetrievalService retrievalService;
    private final FastApiService fastApiService;

    @Value("${rag.search.top-k:5}")
    private int topK;

    /**
     * 근거를 붙여 챗봇에 질의한다.
     *
     * @param admin 관리자 여부. 권한 선필터 적용 대상을 가른다
     */
    public ChatDomain.Response chat(ChatDomain.Request request, String userId, boolean admin) {
        String question = extractQuestion(request);

        RetrievalResult retrieval = retrievalService.retrieve(question, userId, admin, topK);
        if (retrieval.isEmpty()) {
            // traceId를 인자로 붙이지 않는다 -- 로그 패턴의 %X{traceId}가 모든 줄에 이미 찍는다(P2-11-3).
            // 줄마다 손으로 붙이면 빠뜨린 줄만 추적이 끊기고, 그 사실이 드러나지도 않는다.
            log.info("[RAG] 근거를 찾지 못해 원본 질문만 전달합니다.");
        }

        ChatDomain.Request augmented = augment(request, question, retrieval);

        // 컨트롤러에서 넘어온 인증 컨텍스트를 유지하기 위해 현재 스레드에서 block한다.
        ChatDomain.Response response = fastApiService.chatBotFromSpringToFastApi(augmented, userId).block();

        fastApiService.saveChatLog(question, response, userId, request.traceId(), retrieval);
        return response;
    }

    /** 마지막 메시지를 현재 질문으로 본다. 앞의 메시지들은 대화 맥락이다. */
    private String extractQuestion(ChatDomain.Request request) {
        List<ChatDomain.Request.Message> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.get(messages.size() - 1).content();
    }

    /**
     * 마지막 user 메시지를 "근거 + 지시문 + 원질문" 형태로 교체한다.
     * 근거가 없으면 원본 요청을 그대로 돌려준다.
     */
    private ChatDomain.Request augment(ChatDomain.Request request, String question,
                                       RetrievalResult retrieval) {
        if (retrieval.isEmpty()) {
            return request;
        }
        String augmentedContent = buildPrompt(question, retrieval.chunks());

        List<ChatDomain.Request.Message> messages = new ArrayList<>(request.messages());
        int last = messages.size() - 1;
        messages.set(last, new ChatDomain.Request.Message(messages.get(last).role(), augmentedContent));

        return new ChatDomain.Request(messages, request.lang(), request.traceId());
    }

    /**
     * 근거를 붙인 프롬프트를 만든다.
     *
     * <p>"자료에 없으면 모른다고 하라"는 지시가 중요하다. 이것이 없으면 모델이 근거를 무시하고
     * 자체 지식으로 답해버려, 근거를 붙인 의미가 사라지고 환각을 거르지 못한다.
     */
    private String buildPrompt(String question, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("[참고 자료]\n");

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            sb.append(i + 1).append(". (")
                    .append(describe(chunk.sourceType()))
                    .append(" #").append(chunk.sourceId()).append(") ")
                    .append(chunk.content()).append('\n');
        }

        sb.append("\n위 참고 자료를 근거로 답변하세요. ")
                .append("자료에 없는 내용은 추측하지 말고 모른다고 답하세요. ")
                .append("답변에 사용한 자료 번호를 함께 알려주세요.\n\n")
                .append("[질문]\n")
                .append(question);

        return sb.toString();
    }

    private String describe(SourceType sourceType) {
        return switch (sourceType) {
            case WORK_LOG, WORK_LOG_TRANSLATION -> "작업일지";
            case SAFETY_COURSE -> "안전교육";
            case CHECKLIST_ITEM -> "점검항목";
        };
    }
}
