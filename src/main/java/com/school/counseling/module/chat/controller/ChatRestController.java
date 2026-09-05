package com.school.counseling.module.chat.controller;

import com.school.counseling.common.dto.ApiResponse;
import com.school.counseling.common.util.SecurityUtils;
import com.school.counseling.module.chat.dto.ChatMessageDto;
import com.school.counseling.module.chat.dto.ConversationDto;
import com.school.counseling.module.chat.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatRestController {

    private final ChatService chatService;

    @PostMapping("/conversations")
    public ResponseEntity<ApiResponse<ConversationDto>> createConversation(@Valid @RequestBody ConversationDto request) {
        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        ConversationDto response = chatService.createConversation(request, currentUserId);
        return ResponseEntity.ok(ApiResponse.success(response, "Khởi tạo cuộc hội thoại thành công"));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<ApiResponse<ChatMessageDto>> sendMessage(
            @PathVariable Long conversationId,
            @RequestParam("content") String content,
            @RequestParam(value = "senderType", required = false, defaultValue = "STUDENT") String senderType,
            @RequestParam(value = "file", required = false) MultipartFile file) {

        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        ChatMessageDto response = chatService.sendMessage(conversationId, content, senderType, currentUserId, file);
        return ResponseEntity.ok(ApiResponse.success(response, "Gửi tin nhắn thành công"));
    }

    @GetMapping("/conversations/{conversationId}/history")
    public ResponseEntity<ApiResponse<List<ChatMessageDto>>> getHistory(@PathVariable Long conversationId) {
        List<ChatMessageDto> history = chatService.getConversationHistory(conversationId);
        return ResponseEntity.ok(ApiResponse.success(history, "Lấy lịch sử hội thoại thành công"));
    }

    @GetMapping("/my-conversations")
    public ResponseEntity<ApiResponse<List<ConversationDto>>> getMyConversations() {
        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        if (currentUserId == null) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        List<ConversationDto> list = chatService.getConversationsByUser(currentUserId);
        return ResponseEntity.ok(ApiResponse.success(list));
    }
}
