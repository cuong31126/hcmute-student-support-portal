package com.school.counseling.module.ticket.controller;

import com.school.counseling.common.util.SecurityUtils;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import com.school.counseling.module.ticket.dto.CreateTicketRequest;
import com.school.counseling.module.ticket.dto.TicketReplyRequest;
import com.school.counseling.module.ticket.dto.TicketResponseDto;
import com.school.counseling.module.ticket.dto.TicketSummaryDto;
import com.school.counseling.module.ticket.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class TicketWebController {

    private final TicketService ticketService;
    private final DepartmentRepository departmentRepository;

    @GetMapping("/create")
    @PreAuthorize("isAuthenticated()")
    public String createTicketPage(@RequestParam(value = "title", required = false, defaultValue = "") String title, Model model) {
        model.addAttribute("departments", departmentRepository.findByIsActiveTrue());
        model.addAttribute("initialTitle", title);
        return "ticket/create";
    }

    @PostMapping("/create")
    @PreAuthorize("isAuthenticated()")
    public String handleCreateTicket(
            @Valid @ModelAttribute CreateTicketRequest request,
            BindingResult bindingResult,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            RedirectAttributes redirectAttributes,
            Model model) {

        if (bindingResult.hasErrors()) {
            model.addAttribute("departments", departmentRepository.findByIsActiveTrue());
            model.addAttribute("initialTitle", request.getTitle());
            return "ticket/create";
        }

        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto created = ticketService.createTicket(request, currentUserId, files);

        if (created.getGuestToken() != null) {
            redirectAttributes.addFlashAttribute("successMessage", "Yêu cầu đã được gửi thành công! Mã tra cứu: " + created.getTicketCode());
            return "redirect:/tickets/guest-track?token=" + created.getGuestToken();
        }

        redirectAttributes.addFlashAttribute("successMessage", "Tạo yêu cầu tư vấn thành công! Mã: " + created.getTicketCode());
        return "redirect:/tickets/my-tickets";
    }

    @GetMapping("/my-tickets")
    @PreAuthorize("isAuthenticated()")
    public String myTicketsPage(Model model) {
        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        // ── Dùng TicketSummaryDto (1 SQL, nhẹ) thay vì TicketResponseDto (N+1 query) ──
        List<TicketSummaryDto> tickets = ticketService.getMyTicketSummaries(currentUserId);
        model.addAttribute("tickets", tickets);
        return "ticket/my-tickets";
    }

    @GetMapping("/guest-track")
    public String guestTrackPage(@RequestParam("token") String token, Model model) {
        TicketResponseDto ticket = ticketService.getTicketByGuestToken(token);
        model.addAttribute("ticket", ticket);
        return "ticket/guest-track";
    }

    @PostMapping("/guest-track/rate")
    public String handleGuestRateTicket(
            @RequestParam("token") String token,
            @RequestParam("rating") Integer rating,
            @RequestParam(value = "feedback", required = false) String feedback,
            RedirectAttributes redirectAttributes) {

        ticketService.rateTicketByGuest(token, rating, feedback);
        redirectAttributes.addFlashAttribute("successMessage", "Cảm ơn bạn đã gửi đánh giá hài lòng cho buổi tư vấn!");
        return "redirect:/tickets/guest-track?token=" + token;
    }

    @ResponseBody
    @PostMapping("/guest-track/api/rate")
    public org.springframework.http.ResponseEntity<com.school.counseling.common.dto.ApiResponse<TicketResponseDto>> handleGuestRateTicketAjax(
            @RequestParam("token") String token,
            @RequestParam("rating") Integer rating,
            @RequestParam(value = "feedback", required = false) String feedback) {

        TicketResponseDto ticket = ticketService.rateTicketByGuest(token, rating, feedback);
        return org.springframework.http.ResponseEntity.ok(
                com.school.counseling.common.dto.ApiResponse.success(ticket, "Cảm ơn bạn đã gửi đánh giá hài lòng!")
        );
    }

    @GetMapping("/detail/{ticketId}")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public String ticketDetailPage(@PathVariable Long ticketId, Model model) {
        TicketResponseDto ticket = ticketService.getTicketById(ticketId);
        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        boolean isStaffOrAdmin = SecurityUtils.hasAnyRole("ROLE_STAFF", "ROLE_ADMIN");
        if (isStaffOrAdmin) {
            ticketService.markStaffSeen(ticketId, currentUserId);
        }
        model.addAttribute("ticket", ticket);
        model.addAttribute("currentUserId", currentUserId);
        model.addAttribute("isStaffOrAdmin", isStaffOrAdmin);
        return "ticket/detail";
    }

    @PostMapping("/{ticketId}/reply")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public String handleReplyTicket(
            @PathVariable Long ticketId,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {

        String safeContent = content != null ? content.trim() : "";
        boolean hasFiles = files != null && files.stream().anyMatch(f -> f != null && !f.isEmpty());

        if (!safeContent.isEmpty() || hasFiles) {
            Long responderId = SecurityUtils.getCurrentUserId().orElse(null);
            TicketReplyRequest request = TicketReplyRequest.builder().content(safeContent).build();
            ticketService.replyTicket(ticketId, request, responderId, files);
        }

        return "redirect:/tickets/detail/" + ticketId;
    }

    @PostMapping("/{ticketId}/close")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public String handleCloseTicket(
            @PathVariable Long ticketId,
            @RequestParam(value = "rating", required = false, defaultValue = "5") Integer rating,
            @RequestParam(value = "feedback", required = false) String feedback,
            RedirectAttributes redirectAttributes) {

        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        ticketService.closeTicket(ticketId, currentUserId, rating, feedback);

        redirectAttributes.addFlashAttribute("successMessage", "Cảm ơn bạn đã gửi đánh giá hài lòng CSAT và xác nhận đóng yêu cầu!");
        return "redirect:/tickets/detail/" + ticketId;
    }

    @PostMapping("/{ticketId}/rate")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public String handleRateTicket(
            @PathVariable Long ticketId,
            @RequestParam(value = "rating") Integer rating,
            @RequestParam(value = "feedback", required = false) String feedback,
            RedirectAttributes redirectAttributes) {

        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        ticketService.rateTicket(ticketId, currentUserId, rating, feedback);

        redirectAttributes.addFlashAttribute("successMessage", "Đã ghi nhận đánh giá hài lòng CSAT (" + rating + " sao). Cảm ơn ý kiến đóng góp của bạn!");
        return "redirect:/tickets/detail/" + ticketId;
    }
}
