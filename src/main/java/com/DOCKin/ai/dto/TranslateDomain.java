package com.DOCKin.ai.dto;

public class TranslateDomain{
    public record Request(
                             String source,
                             String target,
                             String traceId){}

    public record ApiRequest(
            String text,
            String source,
            String target,
            String traceId
    ){}

    public record Response(
            String title,
            String content,
            String model,
            String traceId
    ){}
}
