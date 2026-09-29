package com.school.counseling.module.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO tối giản dành riêng cho trang Danh sách Ticket (Dashboard Cán bộ, Lịch sử của SV).
 * Chỉ chứa đúng 11 trường cần thiết cho hiển thị bảng/danh sách.
 * Tránh N+1 Query: Dùng JPQL Constructor Expression thay thế hoàn toàn mapToDto() cũ.
 *
 * QUAN TRỌNG: Class này được dùng trong @Query JPQL - thứ tự constructor PHẢI khớp với Query.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketSummaryDto {

    private Long id;
    private String ticketCode;
    private String title;

    private Long departmentId;
    private String departmentName;

    private String status;
    private String priority;
    private LocalDateTime dueDate;
    private LocalDateTime createdAt;

    private String creatorOrGuestName;   // Creator fullName hoặc guestName nếu không đăng nhập
    private String assignedStaffName;    // Null nếu chưa được cán bộ tiếp nhận

    // Tính toán in-memory, không cần thêm cột DB
    public boolean isOverdue() {
        return dueDate != null
                && LocalDateTime.now().isAfter(dueDate)
                && !"RESOLVED".equals(status)
                && !"CLOSED".equals(status);
    }

    public boolean isDueSoon() {
        if (dueDate == null || "RESOLVED".equals(status) || "CLOSED".equals(status)) return false;
        return LocalDateTime.now().isBefore(dueDate)
                && LocalDateTime.now().isAfter(dueDate.minusHours(24));
    }
}
