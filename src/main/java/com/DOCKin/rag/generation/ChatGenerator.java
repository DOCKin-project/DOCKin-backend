package com.DOCKin.rag.generation;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.rag.dto.RetrievalResult;

/**
 * RAG의 G(Generation). 근거가 붙은 프롬프트를 받아 답변을 만든다.
 *
 * <p>구현은 둘이다. {@link FastApiChatGenerator}가 기본이고 팀원 FastAPI를 부른다.
 * {@link StubChatGenerator}는 {@code ai.chatbot.stub=true}일 때만 뜨며 모델 없이 근거만 돌려준다 --
 * FastAPI가 없는 환경(심사·로컬 clone)에서 검색(R)·권한 선필터·프롬프트 조립까지를 끝까지 보여주기 위한 것이다(#95).
 *
 * <p>둘을 가르는 기준은 <b>답변의 질이 아니라 근거의 출처</b>다. 어느 쪽이든 근거는 {@code RetrievalService}가
 * 권한 선필터를 거쳐 고른 것이고, 생성기는 그 목록을 바꾸지 못한다.
 */
public interface ChatGenerator {

    /**
     * @param augmented 마지막 user 메시지에 근거와 지시문이 녹아 있는 요청. 근거가 없으면 원본 그대로
     * @param retrieval 근거 검색 결과. 스텁은 이것으로 답을 만들고, FastAPI 구현은 쓰지 않는다(프롬프트에 이미 있다)
     */
    ChatDomain.Response.Result generate(ChatDomain.Request augmented, RetrievalResult retrieval, String userId);
}
