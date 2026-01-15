package com.DOCKin.global.config;

import com.DOCKin.global.error.BusinessException;
import com.DOCKin.global.error.ErrorCode;
import com.DOCKin.global.security.jwt.JwtUtil;
import com.DOCKin.model.Member.Member;
import com.DOCKin.repository.MemberRepository;
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
import java.util.concurrent.ConcurrentHashMap;

//jwt와 상태관리 때문에 쓰임
@Slf4j
@Component
@RequiredArgsConstructor
public class StompHandler implements ChannelInterceptor {
   private final JwtUtil jwtUtil;
   private final MemberRepository memberRepository;

   private static final Map<String, String> onlineUsers = new ConcurrentHashMap<>();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel){
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if(StompCommand.CONNECT == accessor.getCommand()){
            String token = accessor.getFirstNativeHeader("token");

            try{
                //토큰이 없거나 유효하지 않으면 예외 발생
                if(token == null || !jwtUtil.isValidToken(token)){
                    throw new BusinessException(ErrorCode.INVALID_TOKEN);
                }

                String userId = jwtUtil.getUserId(token);

                if(accessor.getSessionAttributes()!=null){
                    accessor.getSessionAttributes().put("userId",userId);
                }

                //접속사 명단에 추가 (상태관리용)
                onlineUsers.put(accessor.getSessionId(),userId);
                log.info("유저 인증 성공: userId={}, 현재 접속자 수={}",userId,onlineUsers.size());
            } catch (Exception e){
                log.error("웹소켓 인증 실패: {}",e.getMessage());
                throw new MessageDeliveryException("인증 실패: "+e.getMessage());
            }
        }

        else if(StompCommand.SUBSCRIBE == accessor.getCommand()){
            String destination = accessor.getDestination();
            String userId = (String) accessor.getSessionAttributes().get("userId");
            String zoneId = extractZoneId(destination);

            Member member = memberRepository.findByUserId(userId)
                    .orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));

            if(!member.getShipYardArea().equals(zoneId)){
                throw new MessageDeliveryException("해당 구역 채팅방에 접근 권한이 없습니다");
            }
        }
        return  message;
    }

    public String extractZoneId(String destination){
        if(destination==null || !destination.contains("/sub/zone/")){
            return null;
        }
        return  destination.replace("/sub/zone/","");
    }
}


