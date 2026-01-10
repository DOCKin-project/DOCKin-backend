package com.DOCKin.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessageRequestDto {
    private Integer roomId;
    private String senderId;
    private String content;
    private MessageType messageType;
    private String fileUrl;
}

