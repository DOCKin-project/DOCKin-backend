package com.DOCKin.ai.service;

import com.DOCKin.ai.dto.ChatDomain;
import com.DOCKin.ai.dto.TranslateDomain;
import com.DOCKin.ai.dto.OnlineTranslateDomain;
import com.DOCKin.ai.model.ChatLog;
import com.DOCKin.ai.model.TranslateLog;
import com.DOCKin.ai.repository.ChatLogRepository;
import com.DOCKin.ai.repository.TranslateRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.worklog.model.WorkLog;
import com.DOCKin.worklog.repository.WorkLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;


@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class FastApiService {
    private final ChatLogRepository chatLogRepository;
    private final WebClient fastApiWebClient;
    private final WorkLogRepository workLogsRepository;
    private final TranslateRepository translateRepository;
    private final SttService sttService;

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
            MultipartFile file, String source, String target, String traceId, String token){

        return sttService.processStt(file,traceId,token,source)
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

    // 작업일지 번역된거 저장
    @Transactional
    public TranslateDomain.Response saveTranslateLog(Long logId, TranslateDomain.Request request, String userId) {
        // 1. 원본 로그 조회
        WorkLog workLogEntity = workLogsRepository.findById(logId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOG_NOT_FOUND));

        // 2. 제목 번역용 요청 생성
        TranslateDomain.ApiRequest titleReq = new TranslateDomain.ApiRequest(
                workLogEntity.getTitle(), "ko", request.target(), request.traceId());

        // 3. 본문 번역용 요청 생성
        TranslateDomain.ApiRequest contentReq = new TranslateDomain.ApiRequest(
                workLogEntity.getLogText(), "ko", request.target(), request.traceId());

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

        // 5. DB 저장.
        // work_log_translations에 UNIQUE(log_id, language_code)가 생겨 무조건 save하면 제약 위반이 난다.
        // 같은 작업일지를 같은 언어로 다시 번역하면 새 행이 아니라 기존 행을 갱신한다 —
        // 중복 행이 쌓이면 RAG 교차언어 색인에서 같은 문서가 여러 번 색인되어 검색 결과가 오염된다.
        translateRepository.findByWorkLogsLogIdAndLanguageCode(logId, request.target())
                .ifPresentOrElse(
                        existing -> existing.updateTranslation(
                                workLogEntity.getTitle(), transTitle,
                                workLogEntity.getLogText(), transContent,
                                request.traceId()),
                        () -> translateRepository.save(TranslateLog.builder()
                                .traceId(request.traceId())
                                .workLogs(workLogEntity)
                                .userId(userId)
                                .originalTitle(workLogEntity.getTitle())
                                .translatedTitle(transTitle)
                                .originalText(workLogEntity.getLogText())
                                .translatedText(transContent)
                                .languageCode(request.target())
                                .build()));

        // 6. Response DTO 구조에 맞춰서 리턴
        return new TranslateDomain.Response(
                transTitle,
                transContent,
                modelName,
                request.traceId()
        );
    }
}