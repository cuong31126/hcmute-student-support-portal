package com.school.counseling.module.ticket.dto;

import com.school.counseling.module.chat.dto.ChatMessageDto.AttachmentDto;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TicketResponseDto {

    private Long id;
    private String ticketCode;
    private String title;
    private String description;
    
    private Long departmentId;
    private String departmentName;

    private Long creatorId;
    private String creatorName;
    private String guestName;
    private String guestEmail;
    private String guestToken;

    private Long assignedStaffId;
    private String assignedStaffName;

    private String priority;
    private String status;
    private LocalDateTime dueDate;
    private boolean isOverdue;
    private boolean isDueSoon;

    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;
    private Integer rating;

    private List<AttachmentDto> attachments;
    private List<HistoryDto> histories;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HistoryDto {
        private Long id;
        private String actorName;
        private String fromStatus;
        private String toStatus;
        private String actionNote;
        private LocalDateTime createdAt;
    }
}
