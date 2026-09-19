package com.DOCKin.ai.service;

import com.DOCKin.ai.dto.SttDomain;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.util.AudioConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.File;

@Service
@RequiredArgsConstructor
@Slf4j
public class SttService {
    private final AudioConverter audioConverter;
    private final WebClient fastApiWebClient;

    /**
     * 사용자의 {@code Authorization} 헤더를 더는 받지 않는다(P2-20-7). FastAPI는 그 헤더를 읽지 않고
     * {@code X-Service-Token}만 본다({@code DOCKin-aiserver app/core/security.py}) — 사용자 JWT를 다른 서비스로
     * 흘려보내기만 하던 인자였다. 서비스 토큰은 {@code fastApiWebClient}가 기본 헤더로 싣는다.
     */
    public Mono<SttDomain.Response> processStt(MultipartFile file,String traceId,String lang){
        try{
            File wavFile = audioConverter.convertToWav(file);

            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file",new FileSystemResource(wavFile))
                    .filename("speech.wav")
                    .contentType(MediaType.parseMediaType("audio/wav"));

            if(lang!=null){
                builder.part("lang",lang);
            }
            builder.part("traceId",traceId);

            return fastApiWebClient.post()
                    .uri("/api/worklogs/stt")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    // 번역·챗봇은 서버 오류를 BusinessException으로 바꾸는데 여기만 빠져 있었다(#117).
                    // 빠지면 WebClientResponseException이 그대로 올라가 실시간 통역은 400·413·502가 전부 500이 되고,
                    // 작업일지 쪽은 catch(Exception)에 삼켜졌다. 서버의 detail.message는 로그에만 남긴다.
                    .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(body -> toBusinessException(response.statusCode().value(), body)))
                    .bodyToMono(SttDomain.Response.class)
                    .doFinally(signalType -> {
                        if(wavFile.exists()) wavFile.delete();
                    });

        } catch (Exception e){
            log.error("STT 처리 중 오류 발생:{}",e.getMessage());
            return Mono.error(new BusinessException(ErrorCode.STT_CONVERSION_ERROR));
        }
    }

    /**
     * FastAPI STT 오류 -> 도메인 예외. 서버(`app/routers/stt.py`)는 400(오디오 아님)·413(너무 큼)·502(whisper 실패)를
     * {@code {"detail": {"message", "traceId", "reason"}}}로 낸다. 413만 따로 살리고 나머지는 STT 오류로 묶는다 --
     * 사용자가 할 수 있는 일이 다른 것은 413(파일을 줄인다)뿐이다.
     */
    static BusinessException toBusinessException(int status, String body) {
        log.warn("[STT] FastAPI {} - {}", status, body);
        return new BusinessException(status == 413
                ? ErrorCode.PAYLOAD_TOO_LARGE
                : ErrorCode.STT_CONVERSION_ERROR);
    }
}
