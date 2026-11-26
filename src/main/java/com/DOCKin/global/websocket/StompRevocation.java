package com.DOCKin.global.websocket;

import com.DOCKin.chat.event.ChatMembersRemoved;
import com.DOCKin.global.security.jwt.JwtBlacklist;
import com.DOCKin.member.event.AccessTokenRevoked;
import com.DOCKin.member.event.MemberWithdrawn;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SubscriptionRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.CloseStatus;

/**
 * 권한이 사라진 순간 <b>이미 열린</b> WebSocket 연결과 구독을 거둔다.
 *
 * <p>{@code StompHandler}는 CONNECT·SUBSCRIBE 때만 본다. 그 뒤의 변화는 여기가 맡는다.
 * <ul>
 *   <li>방에서 내보냄·나감·방 삭제 → 그 사람의 그 방 구독만 브로커에서 지운다. 소켓과 다른 방 구독은 그대로다.</li>
 *   <li>로그아웃 → 그 토큰으로 붙은 세션을 닫는다.</li>
 *   <li>탈퇴 → 그 사람의 세션을 전부 닫는다.</li>
 * </ul>
 * 셋 다 <b>커밋 뒤</b>다. 롤백된 변경으로 구독을 지우면 멤버인데 메시지를 못 받는다.
 * 다시 붙으려 하면 CONNECT(블랙리스트·탈퇴)와 SUBSCRIBE(멤버십) 검사가 막는다.
 */
@Slf4j
@Component
public class StompRevocation {

    private static final CloseStatus LOGGED_OUT = CloseStatus.POLICY_VIOLATION.withReason("logged out");
    private static final CloseStatus WITHDRAWN = CloseStatus.POLICY_VIOLATION.withReason("account withdrawn");

    private final StompSessions sessions;
    private final AbstractBrokerMessageHandler broker;

    public StompRevocation(StompSessions sessions,
                           @Qualifier("simpleBrokerMessageHandler") AbstractBrokerMessageHandler broker) {
        this.sessions = sessions;
        this.broker = broker;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMembersRemoved(ChatMembersRemoved event) {
        if (event.userIds().isEmpty()) {
            return;
        }
        if (!(broker instanceof SimpleBrokerMessageHandler simple)) {
            log.warn("단순 브로커가 아니라 구독을 거둘 수 없습니다. broker={}", broker.getClass().getSimpleName());
            return;
        }
        SubscriptionRegistry registry = simple.getSubscriptionRegistry();
        MultiValueMap<String, String> subscriptions =
                registry.findSubscriptions(probe("/sub/chat/room/" + event.roomId()));

        int revoked = 0;
        for (var entry : subscriptions.entrySet()) {
            String sessionId = entry.getKey();
            String owner = sessions.userIdOf(sessionId).orElse(null);
            if (owner == null || !event.userIds().contains(owner)) {
                continue;
            }
            for (String subscriptionId : entry.getValue()) {
                registry.unregisterSubscription(unsubscribe(sessionId, subscriptionId));
                revoked++;
            }
        }
        if (revoked > 0) {
            log.info("방 구독 회수 - room={}, users={}, subscriptions={}", event.roomId(), event.userIds(), revoked);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccessTokenRevoked(AccessTokenRevoked event) {
        int closed = sessions.closeByTokenFingerprint(JwtBlacklist.fingerprint(event.token()), LOGGED_OUT);
        if (closed > 0) {
            log.info("로그아웃한 토큰의 WebSocket 세션 {}개를 닫았습니다.", closed);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberWithdrawn(MemberWithdrawn event) {
        int closed = sessions.closeByUser(event.userId(), WITHDRAWN);
        if (closed > 0) {
            log.info("탈퇴 회원의 WebSocket 세션 {}개를 닫았습니다. userId={}", closed, event.userId());
        }
    }

    /** 이 목적지의 구독자를 묻는 메시지. 레지스트리는 MESSAGE 타입에만 답한다. */
    private static Message<byte[]> probe(String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> unsubscribe(String sessionId, String subscriptionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.UNSUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subscriptionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
