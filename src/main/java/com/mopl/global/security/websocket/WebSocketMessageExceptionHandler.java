package com.mopl.global.security.websocket;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.web.bind.annotation.ControllerAdvice;

@ControllerAdvice
public class WebSocketMessageExceptionHandler {

    private final WebSocketStompErrorHandler
        stompErrorHandler;

    private final MessageChannel
        clientOutboundChannel;

    public WebSocketMessageExceptionHandler(
        WebSocketStompErrorHandler stompErrorHandler,
        @Qualifier("clientOutboundChannel")
        MessageChannel clientOutboundChannel
    ) {
        this.stompErrorHandler =
            stompErrorHandler;

        this.clientOutboundChannel =
            clientOutboundChannel;
    }

    @MessageExceptionHandler(Throwable.class)
    public void handleMessageException(
        Throwable exception,
        Message<byte[]> clientMessage
    ) {
        Message<byte[]> errorMessage =
            stompErrorHandler
                .handleClientMessageProcessingError(
                    clientMessage,
                    exception
                );

        clientOutboundChannel.send(
            errorMessage
        );
    }
}
