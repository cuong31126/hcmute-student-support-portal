package com.school.counseling.module.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketReplyRequest {

    private String content;

    private String newStatus; // null hoặc RESOLVED, WAITING_STUDENT

    private String actionNote;
}
