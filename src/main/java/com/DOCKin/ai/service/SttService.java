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
                    .bodyToMono(SttDomain.Response.class)
                    .doFinally(signalType -> {
                        if(wavFile.exists()) wavFile.delete();
                    });

        } catch (Exception e){
            log.error("STT 처리 중 오류 발생:{}",e.getMessage());
            return Mono.error(new BusinessException(ErrorCode.STT_CONVERSION_ERROR));
        }
    }
}
