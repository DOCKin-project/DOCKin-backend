package com.DOCKin.rag.service;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.ai.service.FastApiService;
import com.DOCKin.rag.dto.RetrievalResult;
import com.DOCKin.rag.generation.ChatGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 오케스트레이션. 질문을 받아 근거를 찾고, 프롬프트를 조립해 챗봇을 호출하고, 출처를 남긴다.
 *
 * <p>RAG의 R(Retrieval)과 프롬프트 조립이 여기 있고, G(Generation)는 {@link ChatGenerator}가 담당한다 --
 * 기본은 팀원 FastAPI, {@code ai.chatbot.stub=true}면 모델 없는 스텁(#95).
 *
 * <h3>팀원 API 계약을 바꾸지 않는다</h3>
 * 근거를 별도 필드로 보내려면 FastAPI를 수정해야 하는데 그쪽은 담당 범위 밖이다.
 * 대신 <b>user 메시지 {@code content} 안에 근거를 녹여서 보낸다.</b>
 * 기존 {@code /api/chatbot} 요청 스펙을 한 글자도 바꾸지 않고 RAG를 붙일 수 있다.
 *
 * <h3>근거가 없어도 답변은 나간다</h3>
 * 검색 결과가 없거나 임베딩 서버가 죽어도 챗봇 자체는 동작해야 한다.
 * 그 경우 원본 질문을 그대로 전달하고 {@code retrieval_mode}에 상태를 남긴다.
 *
 * <h3>응답에 근거를 싣는다</h3>
 * {@code chat_history}에만 남기던 근거(모드·청크 ID·유사도)를 응답 {@code retrieval}에도 싣는다.
 * "권한 없는 사용자에게는 같은 질문에 근거가 0건"이라는 선필터의 동작이 DB를 열어보지 않아도 보이게 하기 위해서다.
 * 청크 본문은 싣지 않는다 -- 답변 안에 이미 녹아 있고, 출처만 있으면 원본 API로 확인할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService {

    private final RetrievalService retrievalService;
    private final FastApiService fastApiService;
    private final ChatGenerator chatGenerator;

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

        ChatDomain.Response.Result result = chatGenerator.generate(augmented, retrieval, userId);
        ChatDomain.Response response = new ChatDomain.Response(request.traceId(), result, describe(retrieval));

        fastApiService.saveChatLog(question, response, userId, request.traceId(), retrieval);
        return response;
    }

    /** 응답에 실을 근거 요약. {@code chat_history.retrieval_mode}·{@code source_chunk_ids}와 같은 내용이다. */
    static ChatDomain.Response.Retrieval describe(RetrievalResult retrieval) {
        List<ChatDomain.Response.Source> sources = retrieval.chunks().stream()
                .map(c -> new ChatDomain.Response.Source(c.chunkId(), c.sourceType().name(), c.sourceId(), c.score()))
                .toList();
        return new ChatDomain.Response.Retrieval(retrieval.mode().name(), sources);
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
     * 챗봇 서버가 받는 메시지 수 상한(aiserver {@code ChatRequest.messages} max_length=30, 팀원 서버와 같은 스키마).
     * {@code system} 하나를 앞에 붙이므로 사용자 대화는 그보다 하나 적게 남긴다.
     */
    static final int MAX_MESSAGES = 30;

    /**
     * {@code system} 지시를 앞에 두고, 근거가 있으면 마지막 user 메시지를 "자료 태그 + 질문"으로 바꾼다(#177).
     *
     * <p>"자료에 없으면 모른다고 하라"는 지시가 중요하다. 이것이 없으면 모델이 근거를 무시하고
     * 자체 지식으로 답해버려, 근거를 붙인 의미가 사라지고 환각을 거르지 못한다.
     * 그 지시를 자료와 다른 메시지({@code system})에 두는 이유는 {@link RagPrompt} 참고.
     *
     * <p><b>근거가 없어도 답변 언어 지시는 붙인다</b>(#178). 전에는 근거가 없으면 요청을 그대로 보냈는데,
     * 언어 지시는 근거와 무관하다. 근거 지시("자료를 근거로…") 대신 <b>근거 없음 지시</b>를 붙인다(#197) —
     * 지시가 아예 없으면 모델이 일반 지식으로 절차를 지어낼 수 있다.
     */
    private ChatDomain.Request augment(ChatDomain.Request request, String question,
                                       RetrievalResult retrieval) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return request;
        }
        List<ChatDomain.Request.Message> conversation = new ArrayList<>(request.messages());
        String rules;
        if (retrieval.isEmpty()) {
            rules = RagPrompt.noEvidenceRules() + "\n" + RagPrompt.languageRule(request.lang());
        } else {
            int last = conversation.size() - 1;
            conversation.set(last, new ChatDomain.Request.Message(conversation.get(last).role(),
                    RagPrompt.userContent(question, retrieval.chunks())));
            rules = RagPrompt.groundingRules() + "\n" + RagPrompt.languageRule(request.lang());
        }

        List<ChatDomain.Request.Message> messages = new ArrayList<>();
        messages.add(new ChatDomain.Request.Message("system", rules));
        // 오래된 대화부터 버린다. 질문(마지막)은 항상 남는다.
        messages.addAll(conversation.subList(Math.max(0, conversation.size() - (MAX_MESSAGES - 1)),
                conversation.size()));

        return new ChatDomain.Request(messages, request.lang(), request.traceId());
    }
}
