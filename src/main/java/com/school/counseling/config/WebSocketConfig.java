package com.school.counseling.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * @deprecated Phân hệ Chat Realtime Người-Người (WebSocket STOMP Prototype - Chưa triển khai UI).
 * Kiến trúc chính thức của cổng tư vấn QAUTE Portal là AI RAG Chatbot kết hợp Hệ thống Ticket SLA.
 * Xem chi tiết tại: docs/requirements/chat_module_and_ticketing_architecture_guide.md
 */
@Deprecated
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Endpoint kết nối WebSocket cho Web Browser (hỗ trợ SockJS fallback)
        registry.addEndpoint("/ws-chat")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Prefix cho các kênh lắng nghe tin nhắn từ Server -> Client
        registry.enableSimpleBroker("/topic", "/queue");
        
        // Prefix cho các message gửi từ Client -> Server
        registry.setApplicationDestinationPrefixes("/app");
    }
}
