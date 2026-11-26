package com.DOCKin.member.event;

/** 탈퇴가 커밋됐다. 그 사람의 WebSocket을 전부 닫는다. */
public record MemberWithdrawn(String userId) {
}
