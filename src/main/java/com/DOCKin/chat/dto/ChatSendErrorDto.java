package com.DOCKin.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 메시지 발신이 실패했을 때 <b>보낸 사람에게만</b> 가는 통지. {@code /sub/user/{userId}/errors}.
 *
 * <p>STOMP {@code ERROR} 프레임을 쓰지 않는 이유: 프로토콜상 {@code ERROR}는 연결 종료를 뜻하고
 * Spring도 보낸 뒤 세션을 닫는다. 메시지 한 건이 실패했다고 연결을 끊으면 그 뒤 도착할
 * 다른 방의 메시지까지 잃는다. 실패의 반경은 그 한 건이어야 한다(ADR-0008 3절).
 *
 * @param clientMsgId 클라이언트가 붙인 재전송 키. 어느 메시지가 실패했는지 짝을 맞추는 유일한 축이다 —
 *                    서버 ID는 저장이 안 됐으니 없다. 키 없이 보낸 옛 클라이언트면 null
 */
@Schema(description = "채팅 발신 실패 통지 (발신자에게만)")
public record ChatSendErrorDto(
        @Schema(description = "실패한 메시지의 clientMsgId") UUID clientMsgId,
        @Schema(description = "오류 코드", example = "CT003") String code,
        @Schema(description = "오류 메시지") String message) {
}
