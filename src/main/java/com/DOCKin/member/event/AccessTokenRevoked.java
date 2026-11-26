package com.DOCKin.member.event;

/** 로그아웃으로 블랙리스트에 들어간 액세스 토큰(원문, {@code Bearer } 없이). 그 토큰으로 붙은 WebSocket을 닫는다. */
public record AccessTokenRevoked(String token) {
}
