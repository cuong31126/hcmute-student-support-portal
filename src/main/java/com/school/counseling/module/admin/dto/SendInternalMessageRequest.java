package com.school.counseling.module.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendInternalMessageRequest {

    private Long receiverId;

    private Long departmentId;

    private String subject;

    @NotBlank(message = "Nội dung tin nhắn không được để trống")
    private String content;

    private String messageType = "INQUIRY"; // INQUIRY, COMPLAINT, REMINDER, DIRECT_CHAT
}
