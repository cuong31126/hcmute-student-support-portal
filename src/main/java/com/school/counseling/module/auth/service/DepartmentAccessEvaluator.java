package com.school.counseling.module.auth.service;

import com.school.counseling.module.auth.dto.UserPrincipal;
import com.school.counseling.module.ticket.entity.Ticket;
import com.school.counseling.module.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Custom Security Bean hỗ trợ SpEL Expression phân quyền theo Khoa/Phòng
 * Sử dụng: @PreAuthorize("@deptSecurity.canAccessDepartment(#deptId)")
 */
@Component("deptSecurity")
@RequiredArgsConstructor
public class DepartmentAccessEvaluator {

    private final TicketRepository ticketRepository;

    public UserPrincipal getCurrentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal) {
            return (UserPrincipal) auth.getPrincipal();
        }
        return null;
    }

    /**
     * Kiểm tra Cán bộ có thuộc Khoa/Phòng được yêu cầu không (Admin luôn được phép)
     */
    public boolean canAccessDepartment(Long departmentId) {
        UserPrincipal user = getCurrentPrincipal();
        if (user == null) return false;

        // ROLE_ADMIN có toàn quyền trên mọi Khoa/Phòng
        if ("ROLE_ADMIN".equalsIgnoreCase(user.getRoleName())) {
            return true;
        }

        // ROLE_STAFF chỉ được truy cập nếu đúng departmentId
        if ("ROLE_STAFF".equalsIgnoreCase(user.getRoleName())) {
            return Objects.equals(user.getDepartmentId(), departmentId);
        }

        return false;
    }

    /**
     * Kiểm tra Sinh viên có phải là người tạo Ticket hoặc Cán bộ phụ trách Ticket đó không
     */
    public boolean canAccessTicket(Long ticketId) {
        UserPrincipal user = getCurrentPrincipal();
        if (user == null) return false;

        if ("ROLE_ADMIN".equalsIgnoreCase(user.getRoleName())) {
            return true;
        }

        Optional<Ticket> ticketOpt = ticketRepository.findById(ticketId);
        if (ticketOpt.isEmpty()) {
            return false;
        }

        Ticket ticket = ticketOpt.get();

        // Nếu là Sinh viên -> Phải là người tạo Ticket
        if ("ROLE_STUDENT".equalsIgnoreCase(user.getRoleName())) {
            return ticket.getCreator() != null && Objects.equals(ticket.getCreator().getId(), user.getId());
        }

        // Nếu là Staff -> Phải thuộc Khoa quản lý Ticket đó
        if ("ROLE_STAFF".equalsIgnoreCase(user.getRoleName())) {
            return Objects.equals(ticket.getDepartment().getId(), user.getDepartmentId());
        }

        return false;
    }
}
