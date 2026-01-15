package com.DOCKin.global.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketHandler extends TextWebSocketHandler {
    private final List<WebSocketSession> sessionList = new ArrayList<>();

    //접속 시 실행
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception{
        sessionList.add(session);
        String msg = session.getId() + "님이 입장하셨습니다.";

        //리스트에 있는 모든 사람에게 입장 메시지 전송
        for (WebSocketSession wsSession : sessionList) {
                wsSession.sendMessage(new TextMessage(msg+"\n"));
        }
    }

    @Override
    protected  void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception{
       //로그 기록
        log.info("메시지 수신: {}",message.getPayload());
        for (WebSocketSession wsSession : sessionList) {
            wsSession.sendMessage(new TextMessage(session.getId() + "> "+message.getPayload()+ "\n"));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception{
        log.info("전송 에러 발생: ", exception);
        super.handleTransportError(session,exception);
    }


    //퇴장 시 실행
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception{
        String msg = session.getId() + "님이 퇴장하셨습니다.";
        log.info(msg);

        //나간 사람을 리스트에서 삭제
        sessionList.remove(session);

        //남은 사람들에게 퇴장 알림 전송
        for (WebSocketSession wsSession : sessionList) {
            if (wsSession.isOpen()) {
                wsSession.sendMessage(new TextMessage(msg + "\n"));
            }
        }
        super.afterConnectionClosed(session,status);
    }
}
