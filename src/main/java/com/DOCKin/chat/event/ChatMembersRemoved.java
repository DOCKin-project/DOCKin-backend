package com.DOCKin.chat.event;

import java.util.Set;

/**
 * 방에서 빠진 사람들(내보냄·나감·방 삭제). 커밋 뒤 {@code StompRevocation}이 이들의 그 방 구독을 거둔다.
 */
public record ChatMembersRemoved(Integer roomId, Set<String> userIds) {
}
