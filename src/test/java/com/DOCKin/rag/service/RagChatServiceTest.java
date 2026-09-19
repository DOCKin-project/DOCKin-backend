package com.DOCKin.rag.service;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.ai.service.FastApiService;
import com.DOCKin.rag.dto.RetrievalResult;
import com.DOCKin.rag.dto.RetrievedChunk;
import com.DOCKin.rag.generation.ChatGenerator;
import com.DOCKin.rag.model.SourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 근거가 응답에 실리는지, 생성기에는 근거가 녹은 프롬프트가 가는지 (#95).
 * 검색 자체는 {@link RetrievalServiceTest}·{@code CrossLingualRetrievalTest}가 본다.
 */
@ExtendWith(MockitoExtension.class)
class RagChatServiceTest {

    private static final String USER = "worker02";
    private static final String TRACE = "t-1";

    @Mock private RetrievalService retrievalService;
    @Mock private FastApiService fastApiService;
    @Mock private ChatGenerator chatGenerator;

    @InjectMocks private RagChatService ragChatService;

    private static ChatDomain.Request ask(String question) {
        return new ChatDomain.Request(
                List.of(new ChatDomain.Request.Message("user", question)), "vi", TRACE);
    }

    private static RetrievedChunk chunk(long chunkId, long logId, double score) {
        return new RetrievedChunk(chunkId, SourceType.WORK_LOG, logId, 0, "용접 와이어 송급 불량", score);
    }

    @Test
    @DisplayName("근거를 찾으면 응답 retrieval에 모드·청크 ID·유사도가 실리고, 생성기에는 근거가 녹은 프롬프트가 간다")
    void 근거_있음() {
        RetrievalResult found = new RetrievalResult(RetrievalResult.RetrievalMode.VECTOR,
                List.of(chunk(11, 1, 0.83), chunk(12, 2, 0.71)));
        when(retrievalService.retrieve(anyString(), eq(USER), eq(false), anyInt())).thenReturn(found);
        when(chatGenerator.generate(any(), eq(found), eq(USER)))
                .thenReturn(new ChatDomain.Response.Result("답"));

        ChatDomain.Response response = ragChatService.chat(ask("Dây hàn bị kẹt?"), USER, false);

        assertEquals(TRACE, response.traceId());
        assertEquals("답", response.result().reply());
        assertEquals("VECTOR", response.retrieval().mode());
        assertEquals(List.of(11L, 12L), response.retrieval().sources().stream()
                .map(ChatDomain.Response.Source::chunkId).toList());
        assertEquals("WORK_LOG", response.retrieval().sources().get(0).sourceType());
        assertEquals(0.83, response.retrieval().sources().get(0).score(), 1e-9);

        ArgumentCaptor<ChatDomain.Request> sent = ArgumentCaptor.forClass(ChatDomain.Request.class);
        verify(chatGenerator).generate(sent.capture(), eq(found), eq(USER));
        String prompt = sent.getValue().messages().get(0).content();
        assertTrue(prompt.startsWith("[참고 자료]"), "근거가 프롬프트 앞에 붙는다");
        assertTrue(prompt.contains("(작업일지 #1)"), "출처 번호가 프롬프트에 있다");
        assertTrue(prompt.endsWith("Dây hàn bị kẹt?"), "원질문이 마지막에 온다");

        verify(fastApiService).saveChatLog(eq("Dây hàn bị kẹt?"), eq(response), eq(USER), eq(TRACE), eq(found));
    }

    @Test
    @DisplayName("권한 밖이라 근거가 없으면 retrieval은 NONE·빈 목록이고, 원본 질문이 그대로 생성기에 간다")
    void 근거_없음() {
        RetrievalResult none = RetrievalResult.none();
        when(retrievalService.retrieve(anyString(), eq(USER), eq(false), anyInt())).thenReturn(none);
        when(chatGenerator.generate(any(), eq(none), eq(USER)))
                .thenReturn(new ChatDomain.Response.Result("모름"));

        ChatDomain.Request original = ask("Dây hàn bị kẹt?");
        ChatDomain.Response response = ragChatService.chat(original, USER, false);

        assertEquals("NONE", response.retrieval().mode());
        assertTrue(response.retrieval().sources().isEmpty());
        // 근거가 없으면 요청 객체를 새로 만들지 않는다 - 프롬프트 지시문이 붙으면 안 된다.
        verify(chatGenerator).generate(eq(original), eq(none), eq(USER));
        verify(fastApiService, never()).chatBotFromSpringToFastApi(any(), anyString());
    }
}
