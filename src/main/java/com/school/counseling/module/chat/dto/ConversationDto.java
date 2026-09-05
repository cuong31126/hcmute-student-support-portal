package com.school.counseling.module.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationDto {

    private Long id;

    @NotBlank(message = "Tiêu đề hội thoại không được để trống")
    private String title;

    private Long creatorId;
    private String creatorName;
    private String guestEmail;

    @NotNull(message = "Đơn vị tư vấn không được để trống")
    private Long departmentId;
    private String departmentName;

    private String status;
    private LocalDateTime createdAt;
    private int totalMessages;
}
