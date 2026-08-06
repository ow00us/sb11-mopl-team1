package com.mopl.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.directmessage.websocket.DirectMessageAuthorizationInterceptor;
import com.mopl.global.security.websocket.StompAuthChannelInterceptor;
import com.mopl.global.security.websocket.StompDestinationAuthorizationInterceptor;
import com.mopl.global.security.websocket.StompMessagingControllerAdvice;
import com.mopl.global.security.websocket.WebSocketStompErrorHandler;
import com.mopl.watchingsession.websocket.ChatSenderCachingInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final StompDestinationAuthorizationInterceptor stompDestinationAuthorizationInterceptor;
    private final WebSocketStompErrorHandler webSocketStompErrorHandler;
    private final DirectMessageAuthorizationInterceptor directMessageAuthorizationInterceptor;
    private final ChatSenderCachingInterceptor chatSenderCachingInterceptor;

    @Value("${app.websocket.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns(allowedOrigins)
            .withSockJS();
        registry.setErrorHandler(webSocketStompErrorHandler);

    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/pub"); // 클라이언트 -> 서버 (SEND)
        registry.enableSimpleBroker("/sub") // 서버 -> 클라이언트 (SUBSCRIBE)
            .setHeartbeatValue(new long[]{4000, 4000})
            .setTaskScheduler(heartbeatTaskScheduler());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
            stompAuthChannelInterceptor,
            stompDestinationAuthorizationInterceptor,
            chatSenderCachingInterceptor,
            directMessageAuthorizationInterceptor
        );
    }

    @Bean
    public TaskScheduler heartbeatTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

}
