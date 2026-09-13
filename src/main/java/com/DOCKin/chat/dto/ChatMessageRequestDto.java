package com.DOCKin.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "채팅메시지 req dto")
public class ChatMessageRequestDto {

    @Schema(description = "채팅방 id",requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message="채팅방 ID는 필수입니다.")
    private Integer roomId;

    @Schema(description = "보내는 사람 사원번호",requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message="보내는 사람의 사원번호는 필수입니다.")
    private String senderId;

    @Schema(description = "보내는 내용",requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message="메시지 내용의 기입은 필수입니다.")
    private String content;

    @Schema(description = "보낼 메시지 종류 (TEXT/IMAGE/FILE)",example = "File",requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "메시지 종류는 필수입니다.")
    private MessageType messageType;

    @Schema(description = "파일 링크", example = "파일 보낼 때에만 사용함")
    private String fileUrl;
    @Schema(description = "클라이언트가 발급한 재전송 키(UUID). 같은 방에서 같은 키는 한 번만 저장된다", example = "6f1c2e2a-3b7d-4c0e-9a1f-2d8e5b7c4a10")
    private UUID clientMsgId;
}

