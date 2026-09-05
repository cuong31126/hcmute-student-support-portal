package com.school.counseling.module.ticket.controller;

import com.school.counseling.common.dto.ApiResponse;
import com.school.counseling.common.util.SecurityUtils;
import com.school.counseling.module.ticket.dto.TicketResponseDto;
import com.school.counseling.module.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/staff/tickets")
@PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
@RequiredArgsConstructor
public class StaffTicketController {

    private final TicketService ticketService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TicketResponseDto>>> getStaffTickets(
            @RequestParam(value = "departmentId", required = false) Long departmentId,
            @RequestParam(value = "status", required = false, defaultValue = "ALL") String status) {

        Long currentDeptId = departmentId;
        if (currentDeptId == null) {
            currentDeptId = SecurityUtils.getCurrentDepartmentId()
                    .orElseThrow(() -> new IllegalArgumentException("Cán bộ chưa được gán Đơn vị/Khoa quản lý"));
        }

        List<TicketResponseDto> tickets = ticketService.getTicketsByDepartment(currentDeptId, status);
        return ResponseEntity.ok(ApiResponse.success(tickets));
    }

    @PostMapping("/{ticketId}/claim")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public ResponseEntity<ApiResponse<TicketResponseDto>> claimTicket(@PathVariable Long ticketId) {
        Long staffId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new IllegalArgumentException("Cán bộ chưa đăng nhập"));
        TicketResponseDto response = ticketService.claimTicket(ticketId, staffId);
        return ResponseEntity.ok(ApiResponse.success(response, "Tiếp nhận Ticket thành công"));
    }

    @PostMapping("/{ticketId}/resolve")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public ResponseEntity<ApiResponse<TicketResponseDto>> resolveTicket(
            @PathVariable Long ticketId,
            @RequestParam(value = "note", required = false) String note) {

        Long staffId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto response = ticketService.resolveTicket(ticketId, staffId, note);
        return ResponseEntity.ok(ApiResponse.success(response, "Đã hoàn tất giải quyết Ticket"));
    }
}
