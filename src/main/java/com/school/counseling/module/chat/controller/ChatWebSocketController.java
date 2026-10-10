package com.school.counseling.module.chat.controller;

import com.school.counseling.module.chat.dto.ChatMessageDto;
import com.school.counseling.module.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

/**
 * @deprecated Phân hệ Chat Realtime Người-Người (WebSocket Prototype - Chưa triển khai UI).
 * Kiến trúc chính thức sử dụng AI Chatbot Widget (REST API DeferredResult) và Module Ticket SLA.
 * Xem chi tiết tại: docs/requirements/chat_module_and_ticketing_architecture_guide.md
 */
@Deprecated
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final ChatService chatService;

    /**
     * Nhận tin nhắn từ Client gửi tới `/app/chat.sendMessage/{conversationId}`
     * Tự động lưu Database và broadcast ra `/topic/conversation/{conversationId}`
     */
    @MessageMapping("/chat.sendMessage/{conversationId}")
    @SendTo("/topic/conversation/{conversationId}")
    public ChatMessageDto processMessageFromClient(
            @DestinationVariable Long conversationId,
            @Payload ChatMessageDto messageDto) {

        log.info("WebSocket nhận tin nhắn: Room={}, Content={}", conversationId, messageDto.getContent());
        return chatService.sendMessage(
                conversationId,
                messageDto.getContent(),
                messageDto.getSenderType(),
                messageDto.getSenderId(),
                null
        );
    }
}
