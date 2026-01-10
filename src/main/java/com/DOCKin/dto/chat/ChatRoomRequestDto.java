package com.DOCKin.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatRoomRequestDto {
    private String room_name;
    private Boolean is_group;
    private List<String> participantIds;
}
