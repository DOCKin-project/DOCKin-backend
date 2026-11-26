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

        RetrievalResult retrieval = retrievalService.retrieve(searchQuery(request, question), userId, admin, topK);
        if (retrieval.isEmpty()) {
            // traceId를 인자로 붙이지 않는다 -- 로그 패턴의 %X{traceId}가 모든 줄에 이미 찍는다(P2-11-3).
            // 줄마다 손으로 붙이면 빠뜨린 줄만 추적이 끊기고, 그 사실이 드러나지도 않는다.
            log.info("[RAG] 근거를 찾지 못해 원본 질문만 전달합니다.");
        }

        ChatDomain.Request augmented = augment(request, question, retrieval);

        ChatDomain.Response.Result generated = chatGenerator.generate(augmented, retrieval, userId);
        // 작은 모델이 자료 태그를 답에 옮겨 적으면 화면에 그대로 나간다(#201). 대화 기록에도 지운 답을 남긴다.
        ChatDomain.Response.Result result = generated == null
                ? null
                : new ChatDomain.Response.Result(RagPrompt.cleanReply(generated.reply()));
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

    /** 검색어에 붙이는 앞 user 메시지 수 (#198). */
    static final int CONTEXT_TURNS = 2;

    /**
     * 검색어를 만든다 (#198). 현재 질문 뒤에 앞 user 메시지를 최근 것부터 {@link #CONTEXT_TURNS}개 붙인다.
     *
     * <p>전에는 마지막 메시지만 검색했다. "크레인 3호기 누유 기록 있어?" 다음의 "그거 누가 고쳤어?"는
     * 크레인을 모른 채 검색되어 엉뚱한 일지가 근거로 들어갔다. LLM은 앞 대화를 받지만 검색은 못 받았다.
     *
     * <p><b>현재 질문을 맨 앞에 둔다.</b> 임베딩 모델(e5-small)은 512토큰을 넘으면 뒤를 자른다.
     * 앞 대화를 앞에 두면 긴 대화에서 정작 지금 질문이 잘려 나간다.
     *
     * <p>LLM으로 질의를 다시 쓰는 방법도 있지만 호출이 하나 늘고 자체 호스팅에서는 몇 초가 붙는다.
     * 붙이기만으로 모자란지 먼저 잰다(ADR-0013 D4).
     */
    static String searchQuery(ChatDomain.Request request, String question) {
        List<ChatDomain.Request.Message> messages = request.messages();
        if (messages == null || messages.size() < 2) {
            return question;
        }
        StringBuilder sb = new StringBuilder(question);
        int added = 0;
        for (int i = messages.size() - 2; i >= 0 && added < CONTEXT_TURNS; i--) {
            ChatDomain.Request.Message m = messages.get(i);
            if ("user".equals(m.role()) && m.content() != null && !m.content().isBlank()) {
                sb.append('\n').append(m.content());
                added++;
            }
        }
        return sb.toString();
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
