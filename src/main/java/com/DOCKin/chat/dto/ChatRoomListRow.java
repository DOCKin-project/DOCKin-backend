package com.DOCKin.chat.dto;

import java.time.LocalDateTime;

/**
 * 방 목록 한 줄 — {@code chat_members ⋈ chat_rooms} 한 번의 조회가 돌려주는 모양 (ADR-0008 11-1).
 *
 * <p>{@code unreadCount}는 세지 않고 <b>뺀다</b>: {@code last_message_seq − last_read_seq}. 이전에는 방마다
 * {@code chat_members} 조회 + {@code chat_messages} COUNT를 했다 — 방 20개면 쿼리 41개(P2-12-1).
 * 두 정수는 V6가 만들었고, 하나는 저장이 락 안에서 올리고 다른 하나는 읽음 API가 올린다.
 */
public record ChatRoomListRow(
        Integer roomId,
        String roomName,
        String creatorId,
        LocalDateTime createdAt,
        String lastMessageContent,
        LocalDateTime lastMessageAt,
        long lastMessageSeq,
        long lastReadSeq) {

    public long unreadCount() {
        return Math.max(0, lastMessageSeq - lastReadSeq);
    }
}
