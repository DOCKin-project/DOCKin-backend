package com.DOCKin.ai.service;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.ai.dto.SttDomain;
import com.DOCKin.ai.dto.TranslateDomain;
import com.DOCKin.ai.quota.AiQuota;
import com.DOCKin.ai.repository.ChatLogRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.util.AudioConverter;
import com.DOCKin.worklog.repository.WorkLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring↔팀원 FastAPI 경계의 계약 테스트 (#96).
 *
 * <p>{@code src/test/resources/ai-contract/}의 표본(팀원 서버 {@code eaf9a28}의 pydantic 모델을 옮긴 것)을
 * 그대로 되돌려주는 {@link ExchangeFunction} 위에 실제 {@link WebClient}를 올린다. 네트워크 없이
 * Jackson 코덱만 진짜다 -- 이 테스트가 보는 것은 <b>필드 이름</b>이다. #75({@code text} vs {@code logText})처럼
 * 컴파일·CI가 전부 통과하고 붙여 돌려야만 드러나던 불일치가 여기서 떨어진다.
 *
 * <p>요청 쪽도 본다: Spring이 보내는 JSON이 pydantic 모델의 필수 필드를 갖췄는지. 서버는 모르는 키를
 * 무시하지만(pydantic 기본), 필수 키가 빠지면 422다.
 */
class FastApiContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------------------------------------------------------------- chatbot

    @Test
    @DisplayName("챗봇: ChatResponse{reply, model, traceId} 중 reply를 읽고, 요청은 messages[].role/content·lang·traceId")
    void 챗봇_응답과_요청() {
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        FastApiService service = service(client("chatbot-response.json", HttpStatus.OK, sent));

        ChatDomain.Request request = new ChatDomain.Request(
                List.of(new ChatDomain.Request.Message("user", "용접 와이어가 자꾸 멈춰요")), "ko", "trace-chat-1");
        ChatDomain.Response response = service.chatBotFromSpringToFastApi(request, "worker01").block();

        assertEquals("trace-chat-1", response.traceId());
        assertTrue(response.result().reply().startsWith("참고 자료 1에 따르면"), "reply를 읽는다");

        assertEquals("/api/chatbot", sent.get().url().getPath());
        JsonNode json = sentJson(sent.get());
        // pydantic ChatRequest: messages(min 1, 각 role·content min_length 1) 필수, domain·lang·traceId 선택
        assertTrue(json.get("messages").isArray() && json.get("messages").size() >= 1);
        assertEquals("user", json.get("messages").get(0).get("role").asString());
        assertEquals("용접 와이어가 자꾸 멈춰요", json.get("messages").get(0).get("content").asString());
        assertEquals("ko", json.get("lang").asString());
        assertEquals("trace-chat-1", json.get("traceId").asString());
    }

    @Test
    @DisplayName("챗봇: 서버 5xx({detail:{...}})는 CHATBOT_NOT_WORK로 올라온다")
    void 챗봇_오류() {
        FastApiService service = service(client("error-detail.json", HttpStatus.BAD_GATEWAY, new AtomicReference<>()));
        ChatDomain.Request request = new ChatDomain.Request(
                List.of(new ChatDomain.Request.Message("user", "q")), "ko", "t");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.chatBotFromSpringToFastApi(request, "u").block());
        assertEquals(ErrorCode.CHATBOT_NOT_WORK, e.getErrorCode());
    }

    // -------------------------------------------------------------- translate

    @Test
    @DisplayName("번역: TranslateResponse{title, translated, model, traceId}를 전부 읽고, 요청은 source·target(2자+)·text·traceId")
    void 번역_응답과_요청() {
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        FastApiService service = service(client("translate-response.json", HttpStatus.OK, sent));

        TranslateDomain.Response response = service.translateForRealTime(
                new TranslateDomain.ApiRequest("오전 작업 시작 직후부터", "ko", "en", "trace-tr-1")).block();

        assertEquals("CO2 welding machine #3 wire feed failure", response.title());
        assertTrue(response.translated().startsWith("Right after the morning shift"));
        assertEquals("Helsinki-NLP/opus-mt-ko-en", response.model());
        assertEquals("trace-tr-1", response.traceId());

        assertEquals("/api/translate", sent.get().url().getPath());
        JsonNode json = sentJson(sent.get());
        // pydantic TranslateRequest: source·target(min_length 2) 필수, text|logText·title·traceId 선택
        assertEquals("ko", json.get("source").asString());
        assertEquals("en", json.get("target").asString());
        assertEquals("오전 작업 시작 직후부터", json.get("text").asString());
        assertEquals("trace-tr-1", json.get("traceId").asString());
    }

    @Test
    @DisplayName("번역: 서버 5xx는 INTERNAL_SERVER_ERROR로 올라온다")
    void 번역_오류() {
        FastApiService service = service(client("error-detail.json", HttpStatus.BAD_GATEWAY, new AtomicReference<>()));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.translateForRealTime(new TranslateDomain.ApiRequest("x", "ko", "en", "t")).block());
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, e.getErrorCode());
    }

    // -------------------------------------------------------------------- stt

    @Test
    @DisplayName("STT: SttResponse의 인식 결과는 logText다 -- Spring이 그 값을 text()로 읽어야 한다 (#75)")
    void STT_응답(@TempDir Path tmp) throws Exception {
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        AudioConverter converter = mock(AudioConverter.class);
        File wav = Files.createFile(tmp.resolve("speech.wav")).toFile();
        when(converter.convertToWav(any())).thenReturn(wav);
        SttService stt = new SttService(converter, client("stt-response.json", HttpStatus.OK, sent));

        SttDomain.Response response = stt.processStt(
                new MockMultipartFile("file", "a.m4a", "audio/mp4", new byte[]{1, 2, 3}), "trace-stt-1", "ko").block();

        assertEquals("trace-stt-1", response.traceId());
        assertEquals("오전 작업 시작 직후부터 용접 와이어가 간헐적으로 멈추는 현상이 발생했다.", response.text(),
                "서버는 logText로 준다. 이 값이 null이면 실시간 통역이 번역기에 text:null을 보낸다(#75)");
        assertEquals("/api/worklogs/stt", sent.get().url().getPath());
        assertTrue(sent.get().headers().getContentType().isCompatibleWith(MediaType.MULTIPART_FORM_DATA));
    }

    // ---------------------------------------------------------------- helpers

    /** 표본 하나를 돌려주는 WebClient. 보낸 요청은 {@code captured}에 남긴다. */
    private static WebClient client(String fixture, HttpStatus status, AtomicReference<ClientRequest> captured) {
        String body = read(fixture);
        ExchangeFunction exchange = request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(status)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body)
                    .build());
        };
        return WebClient.builder().baseUrl("http://fastapi.test").exchangeFunction(exchange).build();
    }

    private static String read(String fixture) {
        try {
            return new ClassPathResource("ai-contract/" + fixture).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** WebClient만 진짜고 나머지 협력자는 이 테스트에서 호출되지 않는다. */
    private static FastApiService service(WebClient webClient) {
        return new FastApiService(
                mock(ChatLogRepository.class),
                webClient,
                mock(WorkLogRepository.class),
                mock(SttService.class),
                mock(TranslateLogWriter.class),
                mock(TranslateLogReader.class),
                mock(AiQuota.class));
    }

    /** 보낸 요청 본문을 JSON으로. WebClient가 코덱으로 직렬화한 실제 바이트를 모은다. */
    private static JsonNode sentJson(ClientRequest request) {
        MockClientHttpRequest sink = new MockClientHttpRequest(request.method(), request.url());
        request.writeTo(sink, ExchangeStrategies.withDefaults()).block();
        return MAPPER.readTree(sink.getBodyAsString().block());
    }
}
