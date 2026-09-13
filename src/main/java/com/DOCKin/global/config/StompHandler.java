package com.DOCKin.global.config;

import com.DOCKin.chat.repository.ChatMembersRepository;
import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.security.jwt.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class StompHandler implements ChannelInterceptor {
    private final JwtUtil jwtUtil;
    private final ChatMembersRepository chatMembersRepository;

    /**
     * 구독을 허용하는 목적지 둘. {@code ChatController}가 보내는 곳과 정확히 같다.
     *
     * <p>이전에는 SUBSCRIBE에서 로그인 여부만 봤다 — 인증만 있으면 아무 방이나
     * {@code /sub/chat/room/{roomId}}로 구독해 실시간으로 다 받을 수 있었고,
     * {@code /sub/user/{남의 id}/rooms}도 마찬가지였다(백로그 P2-18-3).
     * REST 쪽({@code ChatRoomController})은 멤버십을 보는데 WebSocket만 비어 있었다.
     */
    private static final Pattern ROOM_DESTINATION = Pattern.compile("^/sub/chat/room/(\\d+)$");
    private static final Pattern USER_ROOMS_DESTINATION = Pattern.compile("^/sub/user/([^/]+)/rooms$");

    // 접속 중인 세션 관리 (sessionId -> userId)
    private static final Map<String, String> onlineUsers = new ConcurrentHashMap<>();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        // 1. 웹소켓 연결 시도 시 (CONNECT)
        if (StompCommand.CONNECT == accessor.getCommand()) {
            // 헤더에서 'token' 값을 읽어옴
            String token = accessor.getFirstNativeHeader("token");

            // 토큰 값은 남기지 않는다. HTTP 쪽(JwtAuthFilter)에서 지운 결함이 여기 남아 있었다(P2-18-4).
            log.debug("WebSocket 연결 시도 (token {})", token != null ? "있음" : "없음");

            try {
                // 토큰 부재 시 차단
                if (token == null || token.isEmpty()) {
                    throw new MessageDeliveryException("인증 토큰이 누락되었습니다.");
                }

                // Bearer 접두사가 포함되어 있다면 제거 (JwtUtil 설정에 따라 다를 수 있음)
                if (token.startsWith("Bearer ")) {
                    token = token.substring(7);
                }

                // JWT 토큰 유효성 검증
                if (!jwtUtil.isValidToken(token)) {
                    throw new BusinessException(ErrorCode.INVALID_TOKEN);
                }

                // 토큰에서 유저 아이디(UserId) 추출
                String userId = jwtUtil.getUserId(token);

                // 세션 속성에 userId 저장 (나중에 SUBSCRIBE 등에서 꺼내 쓰기 위함)
                if (accessor.getSessionAttributes() != null) {
                    accessor.getSessionAttributes().put("userId", userId);
                }

                // 온라인 유저 맵에 등록
                onlineUsers.put(accessor.getSessionId(), userId);
                log.info("WebSocket 인증 성공: userId={}", userId);

            } catch (Exception e) {
                log.error("WebSocket 인증 실패: {}", e.getMessage());
                // 여기서 에러를 던지면 연결이 거부됩니다.
                throw new MessageDeliveryException("인증 실패: " + e.getMessage());
            }
        }

        // 2. 특정 채널 구독 시도 시 (SUBSCRIBE)
        else if (StompCommand.SUBSCRIBE == accessor.getCommand()) {
            // CONNECT 단계에서 저장했던 userId를 꺼냄
            String userId = (String) accessor.getSessionAttributes().get("userId");

            if (userId == null) {
                throw new MessageDeliveryException("로그인이 필요한 서비스입니다.");
            }
            authorizeSubscription(userId, accessor.getDestination());
            log.info("구독 요청 - 유저: {}, 경로: {}", userId, accessor.getDestination());
        }

        // 3. 연결 해제 시 (DISCONNECT)
        else if (StompCommand.DISCONNECT == accessor.getCommand()) {
            String sessionId = accessor.getSessionId();
            String removedUser = onlineUsers.remove(sessionId);
            log.info("WebSocket 연결 종료 - userId: {}", removedUser);
        }

        return message;
    }

    /**
     * 목적지가 허용된 꼴이고, 그 방의 멤버(또는 그 사용자 본인)일 때만 통과한다.
     *
     * <p>목록에 없는 목적지는 전부 거부한다 — 브로커 prefix {@code /sub} 아래에 새 목적지를
     * 열면 여기에도 적어야 한다. 열어 두고 잊는 것보다 닫아 두고 실패하는 편이 낫다.
     *
     * <p>{@link BusinessException}을 던지면 채널이 {@code MessageDeliveryException}으로 감싸고
     * {@code StompExceptionHandler}가 원인을 꺼내 {@code ERROR} 프레임(code=CT003)으로 돌려준다.
     */
    private void authorizeSubscription(String userId, String destination) {
        if (destination == null) {
            throw new BusinessException(ErrorCode.CHATROOM_AUTHOR);
        }
        Matcher room = ROOM_DESTINATION.matcher(destination);
        if (room.matches()) {
            int roomId = Integer.parseInt(room.group(1));
            if (!chatMembersRepository.existsByChatRoomsRoomIdAndMemberUserId(roomId, userId)) {
                log.warn("구독 거부 - 멤버가 아님: userId={}, roomId={}", userId, roomId);
                throw new BusinessException(ErrorCode.CHATROOM_AUTHOR);
            }
            return;
        }
        Matcher userRooms = USER_ROOMS_DESTINATION.matcher(destination);
        if (userRooms.matches()) {
            if (!userId.equals(userRooms.group(1))) {
                log.warn("구독 거부 - 남의 방 목록: userId={}, destination={}", userId, destination);
                throw new BusinessException(ErrorCode.CHATROOM_AUTHOR);
            }
            return;
        }
        log.warn("구독 거부 - 허용되지 않은 목적지: userId={}, destination={}", userId, destination);
        throw new BusinessException(ErrorCode.CHATROOM_AUTHOR);
    }
}