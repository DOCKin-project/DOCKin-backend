package com.DOCKin.ai.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Stt dto")
public class SttDomain {
    @Schema(description = "stt request")
    public record Request(

            @Schema(description = "바꿀 언어")
            String lang,

            @Schema(description = "추적ID")
            String traceId
    ){}

    /**
     * 팀원 FastAPI {@code SttResponse}의 인식 결과 필드는 {@code logText}다({@code app/schemas/stt.py}).
     * 여기는 {@code text}로 읽고 있어서 실시간 통역이 번역기에 {@code text: null}을 보내고 있었다(#75).
     * {@code @JsonAlias}로 둘 다 받는다 -- pylab 서버는 {@code text}·{@code logText}를 같이 내리고,
     * 팀원 서버는 {@code logText}만 내린다. 계약은 {@code FastApiContractTest}가 지킨다(#96).
     */
    @Schema(description = "stt response")
    public record Response(

            @Schema(description = "추적ID")
            String traceId,

            @Schema(description = "변환된 text. FastAPI 응답의 logText를 여기로 받는다")
            @JsonAlias("logText")
            String text
    ){}
}
