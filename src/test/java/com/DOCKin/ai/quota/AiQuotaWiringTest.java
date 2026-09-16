package com.DOCKin.ai.quota;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.ai.dto.TranslateDomain;
import com.DOCKin.ai.service.FastApiService;
import com.DOCKin.global.security.auth.CustomUserDetails;
import com.DOCKin.global.testsupport.ContainerTestSupport;
import com.DOCKin.member.dto.CustomUserInfoDto;
import com.DOCKin.member.model.UserRole;
import com.DOCKin.rag.service.RagChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검증: 한도가 <b>컨트롤러에 실제로 배선돼</b> 있고, 상한을 넘은 요청은 <b>FastAPI까지 가지 않는다</b>.
 *
 * <p>{@code AiQuotaRedisTest}는 카운터가 세는 것을, {@code GlobalExceptionHandlerTest}는 핸들러가
 * {@code Retry-After}를 붙이는 것을 각각 본다. 둘 사이 — 컨트롤러가 외부 호출 <b>전에</b> 세고,
 * 429가 HTTP로 실제로 나가는가 — 는 여기서만 보인다. 이 기능의 목적이 "비용 상한"이므로
 * 단언의 핵심은 상태 코드가 아니라 <b>서비스 호출 횟수</b>다.
 *
 * <p>핸들러 우선순위도 여기서 잡힌다. {@link AiQuotaExceededException}은 {@code BusinessException}의
 * 자식이라, Spring이 더 구체적인 핸들러를 고르지 않으면 429는 나가되 {@code Retry-After}가 빠진다.
 *
 * <p>외부 호출 서비스는 목이다 — FastAPI를 띄우는 테스트가 아니다. 상한은 1로 낮춰 두 번째 요청에서 바로 걸리게 한다.
 * 시계는 운영과 같은 시스템 시계다. 첫 요청과 두 번째 요청 사이에 자정이 지나면 카운터가 갈려 실패하는데,
 * 그 창은 밀리초 단위라 고정 시계를 위해 컨텍스트를 하나 더 만들 이유가 안 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ai.quota.daily.chatbot=1",
        "ai.quota.daily.worklog-translate=1"
})
@DisplayName("AI 한도 - 컨트롤러 배선")
class AiQuotaWiringTest extends ContainerTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagChatService ragChatService;

    @MockitoBean
    private FastApiService fastApiService;

    private static final String CHAT_BODY = """
            {"messages":[{"role":"user","content":"안전모 규정"}],"lang":"ko","traceId":"t-1"}
            """;

    private static final String TRANSLATE_BODY = """
            {"source":"ko","target":"en","traceId":"t-2"}
            """;

    @Test
    @DisplayName("챗봇: 상한 안은 200, 상한+1은 429 + Retry-After이고 서비스는 한 번만 불린다")
    void chatbot() throws Exception {
        String userId = "wire-chat-" + System.nanoTime();
        when(ragChatService.chat(any(), eq(userId), anyBoolean()))
                .thenReturn(new ChatDomain.Response("t-1", new ChatDomain.Response.Result("답")));

        mockMvc.perform(post("/api/ai/chatbot")
                        .with(user(principal(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHAT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reply").value("답"));

        MvcResult exceeded = mockMvc.perform(post("/api/ai/chatbot")
                        .with(user(principal(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHAT_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("오늘 사용 가능한 AI 호출 횟수를 모두 사용했습니다."))
                .andReturn();

        // 자정까지 남은 초. 시스템 시계라 정확한 값은 못 박지만 자릿수는 박을 수 있다.
        long retryAfter = Long.parseLong(exceeded.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 86_400L);

        // 핵심 단언 — 두 번째 요청은 서비스까지 오지 않았다.
        verify(ragChatService, times(1)).chat(any(), eq(userId), anyBoolean());
    }

    @Test
    @DisplayName("작업일지 번역: 상한+1은 FastAPI를 부르지 않는다")
    void worklogTranslate() throws Exception {
        String userId = "wire-tr-" + System.nanoTime();
        when(fastApiService.saveTranslateLog(anyLong(), any(), eq(userId)))
                .thenReturn(new TranslateDomain.Response("제목", "translated", "m", "t-2"));

        mockMvc.perform(post("/api/ai/translate/1")
                        .with(user(principal(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSLATE_BODY))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/ai/translate/1")
                        .with(user(principal(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSLATE_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        verify(fastApiService, times(1)).saveTranslateLog(anyLong(), any(), eq(userId));
    }

    @Test
    @DisplayName("종류가 다르면 서로 소진시키지 않는다 - 챗봇을 다 써도 번역은 나간다")
    void kindsAreIndependent() throws Exception {
        String userId = "wire-kind-" + System.nanoTime();
        when(ragChatService.chat(any(), eq(userId), anyBoolean()))
                .thenReturn(new ChatDomain.Response("t-1", new ChatDomain.Response.Result("답")));
        when(fastApiService.saveTranslateLog(anyLong(), any(), eq(userId)))
                .thenReturn(new TranslateDomain.Response("제목", "translated", "m", "t-2"));

        mockMvc.perform(post("/api/ai/chatbot").with(user(principal(userId)))
                        .contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/ai/chatbot").with(user(principal(userId)))
                        .contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/ai/translate/1").with(user(principal(userId)))
                        .contentType(MediaType.APPLICATION_JSON).content(TRANSLATE_BODY))
                .andExpect(status().isOk());

        verify(fastApiService, times(1)).saveTranslateLog(anyLong(), any(), eq(userId));
    }

    @Test
    @DisplayName("익명은 401이고 카운터도 서비스도 건드리지 않는다")
    void anonymous() throws Exception {
        mockMvc.perform(post("/api/ai/chatbot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHAT_BODY))
                .andExpect(status().isUnauthorized());

        verify(ragChatService, never()).chat(any(), anyString(), anyBoolean());
    }

    private static CustomUserDetails principal(String userId) {
        return new CustomUserDetails(CustomUserInfoDto.builder()
                .userId(userId)
                .name("배선 테스트")
                .password("n/a")
                .role(UserRole.USER)
                .build());
    }
}
