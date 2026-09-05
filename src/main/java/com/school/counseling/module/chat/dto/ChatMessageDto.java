package com.school.counseling.module.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessageDto {

    private Long id;
    
    @NotNull(message = "ID cuộc hội thoại không được để trống")
    private Long conversationId;

    private Long senderId;
    private String senderName;
    private String senderType; // STUDENT, GUEST, STAFF, AI_BOT

    @NotBlank(message = "Nội dung tin nhắn không được để trống")
    private String content;

    private LocalDateTime createdAt;
    
    private List<AttachmentDto> attachments;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AttachmentDto {
        private Long id;
        private String fileName;
        private String fileUrl;
        private String fileType;
        private Long fileSize;
    }
}
