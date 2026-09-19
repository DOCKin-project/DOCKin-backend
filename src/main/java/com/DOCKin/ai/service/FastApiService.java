package com.DOCKin.ai.service;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.ai.dto.TranslateDomain;
import com.DOCKin.ai.dto.OnlineTranslateDomain;
import com.DOCKin.ai.model.ChatLog;
import com.DOCKin.ai.quota.AiQuota;
import com.DOCKin.ai.quota.AiQuotaKind;
import com.DOCKin.ai.repository.ChatLogRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.worklog.model.WorkLog;
import com.DOCKin.worklog.service.WorkLogsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;


@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class FastApiService {
    private final ChatLogRepository chatLogRepository;
    private final WebClient fastApiWebClient;
    private final WorkLogsService workLogsService;
    private final SttService sttService;
    private final TranslateLogWriter translateLogWriter;
    private final TranslateLogReader translateLogReader;
    private final AiQuota aiQuota;

    //1. 그냥 번역 api
    public Mono<TranslateDomain.Response> translateForRealTime(TranslateDomain.ApiRequest request){
        return fastApiWebClient.post()
                .uri("/api/translate")
                .bodyValue(request)
                .retrieve()
                .onStatus(status->status.isError(), clientResponse->
                        Mono.error(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)))
                .bodyToMono(TranslateDomain.Response.class);
    }

    //1. 실시간 번역 (stt -> 번역)
    public Mono<OnlineTranslateDomain.RtTranslateResponse> realtimeTranslate(
            MultipartFile file, String source, String target, String traceId){

        return sttService.processStt(file,traceId,source)
                .flatMap(sttResponse->{
                    String recognizedText = sttResponse.text();

                    TranslateDomain.ApiRequest apiRequest = new TranslateDomain.ApiRequest(
                            recognizedText, source, target, traceId
                    );

                    return translateForRealTime(apiRequest)
                            .map(transResponse -> {
                                OnlineTranslateDomain.TranslationResult result =
                                        new OnlineTranslateDomain.TranslationResult(
                                                recognizedText,
                                                transResponse.translated()
                                        );

                                return new OnlineTranslateDomain.RtTranslateResponse(traceId, result);
                            });
                });
    }

    // 2. 챗봇 통신 (순수하게 결과만 리턴)
    public Mono<ChatDomain.Response> chatBotFromSpringToFastApi(ChatDomain.Request request, String userId) {
        return fastApiWebClient.post()
                .uri("/api/chatbot")
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        Mono.error(new BusinessException(ErrorCode.CHATBOT_NOT_WORK)))
                .bodyToMono(ChatDomain.Response.Result.class)
                .map(apiResult -> new ChatDomain.Response(request.traceId(), apiResult));
    }

    /**
     * 챗봇 로그 저장.
     *
     * <p>RAG 도입 후 <b>어떤 근거로 답했는지</b>를 함께 남긴다. 잘못된 답변이 나왔을 때
     * 모델이 문제인지 검색이 문제인지 가르는 유일한 단서이고, 임베딩 서버 장애로 키워드 폴백에
     * 떨어진 구간을 사후에 식별할 수 있게 한다.
     *
     * @param question  실제 질문 원문 (근거가 붙기 전). 프롬프트가 아니라 사용자가 입력한 문장을 남긴다
     * @param retrieval 근거 검색 결과. 근거가 없으면 {@code source_chunk_ids}는 null이 된다
     */
    @Transactional
    public void saveChatLog(String question, ChatDomain.Response response, String userId,
                            String traceId, com.DOCKin.rag.dto.RetrievalResult retrieval) {
        ChatLog log = ChatLog.builder()
                .traceId(traceId)
                .userId(userId)
                .userQuery(question)
                .reply(response == null ? null : response.result().reply())
                .sourceChunkIds(retrieval == null ? null : retrieval.toSourceIds())
                .retrievalMode(retrieval == null ? null : retrieval.mode().name())
                .build();
        chatLogRepository.save(log);
    }

    // 3. 작업일지 번역 (통신만)
    public Mono<TranslateDomain.Response> translateLogFromSpringToFastApi(TranslateDomain.Request request) {
        return fastApiWebClient.post()
                .uri("/api/translate")
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(), clientResponse ->
                        clientResponse.bodyToMono(String.class).flatMap(errorBody -> {
                            log.error("### FastApi 400 Error Detail: {}", errorBody);
                            return Mono.error(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
                        })
                )
                .onStatus(status -> status.is5xxServerError(), clientResponse ->
                        Mono.error(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)))
                .bodyToMono(TranslateDomain.Response.class);
    }

    /**
     * 작업일지 번역. <b>FastAPI 호출은 트랜잭션 밖이다.</b> 저장된 번역이 있고 원문이 그대로면 FastAPI에 가지 않는다.
     *
     * <p>{@code NOT_SUPPORTED}는 클래스의 {@code @Transactional(readOnly = true)}를 이 메서드에서 끄는 것이다.
     * 없으면 조회 → FastAPI {@code block()}(최대 60초) → 저장이 한 트랜잭션이 되어 그동안 커넥션을 물고 있다.
     * 경계는 넷으로 갈린다 — 원문 조회는 리포지토리의 짧은 readOnly 트랜잭션, 캐시 조회는 {@link TranslateLogReader#findCached},
     * 번역은 트랜잭션 없음, 저장은 {@link TranslateLogWriter#upsert}. 이유는 그 클래스들 주석에.
     *
     * <h3>캐시 (P2-19-1)</h3>
     * 2026-09-16까지는 같은 일지·같은 언어를 다시 요청해도 FastAPI에 다시 갔다 — {@code work_log_translations}에
     * 저장은 했지만 upsert 판단에만 썼다. 이제 저장된 행의 원문({@code original_title}·{@code original_text})이
     * 지금 원문과 같으면 그 행을 돌려준다({@link TranslateLog#matchesOriginal}). 원문이 바뀌었으면 재번역해 덮어쓴다.
     *
     * <p><b>한도는 미스에서만 깎는다.</b> {@code worklog-translate} 하루 50은 FastAPI 비용을 막는 것이고
     * 히트는 FastAPI에 가지 않는다. 그래서 {@code AiQuota.consume}이 컨트롤러가 아니라 여기, FastAPI 호출 직전에 있다 —
     * "호출 전 INCR, 실패도 1로 센다"(P2-19)는 그대로다.
     *
     * <p>캐시는 사용자와 무관하다 — 키가 {@code (log_id, language_code)}다. 누가 먼저 번역했든 같은 원문의 같은 언어
     * 번역은 하나다. 히트 때 {@code user_id}·{@code trace_id}는 첫 번역 것이 그대로 남는다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TranslateDomain.Response saveTranslateLog(Long logId, TranslateDomain.Request request, String userId) {
        // 1. 원본 조회 — 같은 구역만(#99). 전에는 findById뿐이라 아무 logId나 번역해 줬다.
        //    title·logText는 즉시 로딩 컬럼이라 트랜잭션 밖에서 읽어도 된다.
        WorkLog workLogEntity = workLogsService.requireVisible(logId, userId);
        String originalTitle = workLogEntity.getTitle();
        String originalText = workLogEntity.getLogText();
        // 요청의 source를 쓴다. 전에는 "ko" 하드코딩이라 DTO의 source가 죽은 값이었다 — 없으면 그대로 ko.
        String source = request.source() == null || request.source().isBlank() ? "ko" : request.source();

        // 1-1. 저장된 번역이 이 원문의 것이면 그대로. 리포지토리를 여기서 바로 부르지 않는 이유는 TranslateLogReader에 —
        //      NOT_SUPPORTED 안의 쿼리 메서드는 스코프 끝까지 커넥션을 쥔다.
        Optional<TranslateLogReader.Cached> cached =
                translateLogReader.findCached(logId, request.target(), originalTitle, originalText);
        if (cached.isPresent()) {
            TranslateLogReader.Cached hit = cached.get();
            log.info("[번역] 캐시 히트 logId={} target={} (첫 번역 {})", logId, request.target(), hit.createdAt());
            return new TranslateDomain.Response(
                    hit.translatedTitle(), hit.translatedText(), hit.model(), request.traceId());
        }

        // 1-2. 미스 — 여기서부터 FastAPI 비용이다. 한도는 호출 전에 깎고, 실패도 1로 센다(P2-19).
        aiQuota.consume(AiQuotaKind.WORKLOG_TRANSLATE, userId);

        // 2. 제목 번역용 요청 생성
        TranslateDomain.ApiRequest titleReq = new TranslateDomain.ApiRequest(
                originalTitle, source, request.target(), request.traceId());

        // 3. 본문 번역용 요청 생성
        TranslateDomain.ApiRequest contentReq = new TranslateDomain.ApiRequest(
                originalText, source, request.target(), request.traceId());

        // 4. 각각 통신 (FastAPI 응답의 'translated' 필드를 맵에서 꺼냄)
        // ADR-0002 2-1: 제목/본문 번역은 서로 의존관계가 없어 Mono.zip으로 동시에 보내고
        // 한 번만 block()한다. 순차 호출(300ms+300ms) 대비 늦은 쪽 응답시간(약 300ms) 수준으로 단축된다.
        Mono<java.util.Map> titleMono = fastApiWebClient.post()
                .uri("/api/translate")
                .bodyValue(titleReq)
                .retrieve()
                .bodyToMono(java.util.Map.class);

        Mono<java.util.Map> contentMono = fastApiWebClient.post()
                .uri("/api/translate")
                .bodyValue(contentReq)
                .retrieve()
                .bodyToMono(java.util.Map.class);

        var zipped = Mono.zip(titleMono, contentMono).block();

        if (zipped == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        var titleMap = zipped.getT1();
        var contentMap = zipped.getT2();

        // FastAPI 응답 필드명인 "translated"로 데이터 추출
        String transTitle = ((String) titleMap.get("translated")).trim();
        String transContent = ((String) contentMap.get("translated")).trim();
        String modelName = (String) titleMap.get("model");

        // 5. DB 저장 — 여기서 처음 쓰기 트랜잭션이 열리고, 짧게 끝난다.
        translateLogWriter.upsert(new TranslateLogWriter.Translated(
                logId, userId, request.target(), request.traceId(),
                originalTitle, transTitle, originalText, transContent, modelName));

        // 6. Response DTO 구조에 맞춰서 리턴
        return new TranslateDomain.Response(
                transTitle,
                transContent,
                modelName,
                request.traceId()
        );
    }
}