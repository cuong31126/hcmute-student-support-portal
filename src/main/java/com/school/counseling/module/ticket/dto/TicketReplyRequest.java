package com.school.counseling.module.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketReplyRequest {

    @NotBlank(message = "Nội dung phản hồi không được để trống")
    private String content;

    private String newStatus; // null hoặc RESOLVED, WAITING_STUDENT

    private String actionNote;
}
