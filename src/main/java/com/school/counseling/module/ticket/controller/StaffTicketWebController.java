package com.school.counseling.module.ticket.controller;

import com.school.counseling.common.util.SecurityUtils;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import com.school.counseling.module.notification.service.EmailAsyncService;
import com.school.counseling.module.ticket.dto.TicketResponseDto;
import com.school.counseling.module.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/staff/tickets")
@PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
@RequiredArgsConstructor
public class StaffTicketWebController {

    private final TicketService ticketService;
    private final DepartmentRepository departmentRepository;
    private final EmailAsyncService emailAsyncService;

    @GetMapping
    public String staffDashboard(
            @RequestParam(value = "status", required = false, defaultValue = "ALL") String status,
            Model model) {

        Long departmentId = SecurityUtils.getCurrentDepartmentId().orElse(1L);
        Department dept = departmentRepository.findById(departmentId).orElse(null);

        List<TicketResponseDto> allTickets = ticketService.getTicketsByDepartment(departmentId, "ALL");
        List<TicketResponseDto> filteredTickets = ticketService.getTicketsByDepartment(departmentId, status);

        long countOpen = allTickets.stream().filter(t -> "OPEN".equalsIgnoreCase(t.getStatus())).count();
        long countInProgress = allTickets.stream().filter(t -> "IN_PROGRESS".equalsIgnoreCase(t.getStatus())).count();
        long countOverdue = allTickets.stream().filter(TicketResponseDto::isOverdue).count();
        long countResolved = allTickets.stream().filter(t -> "RESOLVED".equalsIgnoreCase(t.getStatus()) || "CLOSED".equalsIgnoreCase(t.getStatus())).count();

        model.addAttribute("currentDepartment", dept);
        model.addAttribute("tickets", filteredTickets);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("countOpen", countOpen);
        model.addAttribute("countInProgress", countInProgress);
        model.addAttribute("countOverdue", countOverdue);
        model.addAttribute("countResolved", countResolved);

        return "ticket/staff-dashboard";
    }

    @PostMapping("/{ticketId}/claim")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public String handleClaimTicket(@PathVariable Long ticketId, RedirectAttributes redirectAttributes) {
        Long staffId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto ticket = ticketService.claimTicket(ticketId, staffId);

        // Gửi email thông báo cán bộ đã tiếp nhận
        String recipientEmail = ticket.getGuestEmail();
        String recipientName = ticket.getGuestName() != null ? ticket.getGuestName() : ticket.getCreatorName();
        String staffName = SecurityUtils.getCurrentUserPrincipal().map(u -> u.getFullName()).orElse("Cán bộ tư vấn");
        String trackingUrl = (ticket.getGuestToken() != null)
                ? "http://localhost:8080/tickets/guest-track?token=" + ticket.getGuestToken()
                : "http://localhost:8080/tickets/detail/" + ticket.getId();

        if (recipientEmail != null) {
            emailAsyncService.sendTicketClaimedEmail(recipientEmail, recipientName, ticket.getTicketCode(), staffName, ticket.getDepartmentName(), trackingUrl);
        }

        redirectAttributes.addFlashAttribute("successMessage", "Bạn đã tiếp nhận xử lý yêu cầu #" + ticket.getTicketCode());
        return "redirect:/tickets/detail/" + ticketId;
    }

    @PostMapping("/{ticketId}/resolve")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public String handleResolveTicket(
            @PathVariable Long ticketId,
            @RequestParam(value = "note", required = false) String note,
            RedirectAttributes redirectAttributes) {

        Long staffId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto ticket = ticketService.resolveTicket(ticketId, staffId, note);

        // Gửi email kết quả giải đáp
        String recipientEmail = ticket.getGuestEmail();
        String recipientName = ticket.getGuestName() != null ? ticket.getGuestName() : ticket.getCreatorName();
        String trackingUrl = (ticket.getGuestToken() != null)
                ? "http://localhost:8080/tickets/guest-track?token=" + ticket.getGuestToken()
                : "http://localhost:8080/tickets/detail/" + ticket.getId();

        if (recipientEmail != null) {
            emailAsyncService.sendTicketResolvedEmail(recipientEmail, recipientName, ticket.getTicketCode(), note, trackingUrl);
        }

        redirectAttributes.addFlashAttribute("successMessage", "Đã đánh dấu hoàn tất yêu cầu #" + ticket.getTicketCode());
        return "redirect:/tickets/detail/" + ticketId;
    }
}
