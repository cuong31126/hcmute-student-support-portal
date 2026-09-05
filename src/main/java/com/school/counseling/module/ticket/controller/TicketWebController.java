package com.school.counseling.module.ticket.controller;

import com.school.counseling.common.util.SecurityUtils;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import com.school.counseling.module.notification.service.EmailAsyncService;
import com.school.counseling.module.ticket.dto.CreateTicketRequest;
import com.school.counseling.module.ticket.dto.TicketReplyRequest;
import com.school.counseling.module.ticket.dto.TicketResponseDto;
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
    private final EmailAsyncService emailAsyncService;

    @GetMapping("/create")
    public String createTicketPage(@RequestParam(value = "title", required = false, defaultValue = "") String title, Model model) {
        model.addAttribute("departments", departmentRepository.findByIsActiveTrue());
        model.addAttribute("initialTitle", title);
        return "ticket/create";
    }

    @PostMapping("/create")
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

        // Gửi email xác nhận bất đồng bộ
        String recipientEmail = created.getGuestEmail();
        if (recipientEmail == null && currentUserId != null) {
            SecurityUtils.getCurrentUserPrincipal().ifPresent(u -> {
                String trackingUrl = "http://localhost:8080/tickets/detail/" + created.getId();
                emailAsyncService.sendTicketCreatedEmail(u.getEmail(), u.getFullName(), created.getTicketCode(), created.getTitle(), created.getPriority(), created.getDueDate(), trackingUrl);
            });
        } else if (recipientEmail != null) {
            String trackingUrl = "http://localhost:8080/tickets/guest-track?token=" + created.getGuestToken();
            emailAsyncService.sendTicketCreatedEmail(recipientEmail, created.getGuestName(), created.getTicketCode(), created.getTitle(), created.getPriority(), created.getDueDate(), trackingUrl);
        }

        if (created.getGuestToken() != null) {
            redirectAttributes.addFlashAttribute("successMessage", "Yêu cầu đã được gửi thành công! Mã tra cứu: " + created.getTicketCode());
            return "redirect:/tickets/guest-track?token=" + created.getGuestToken();
        }

        redirectAttributes.addFlashAttribute("successMessage", "Tạo yêu cầu tư vấn thành công! Mã: " + created.getTicketCode());
        return "redirect:/tickets/my-tickets";
    }

    @GetMapping("/my-tickets")
    @PreAuthorize("hasRole('STUDENT')")
    public String myTicketsPage(Model model) {
        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        List<TicketResponseDto> tickets = ticketService.getMyTickets(currentUserId);
        model.addAttribute("tickets", tickets);
        return "ticket/my-tickets";
    }

    @GetMapping("/guest-track")
    public String guestTrackPage(@RequestParam("token") String token, Model model) {
        TicketResponseDto ticket = ticketService.getTicketByGuestToken(token);
        model.addAttribute("ticket", ticket);
        return "ticket/guest-track";
    }

    @GetMapping("/detail/{ticketId}")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public String ticketDetailPage(@PathVariable Long ticketId, Model model) {
        TicketResponseDto ticket = ticketService.getTicketById(ticketId);
        model.addAttribute("ticket", ticket);
        return "ticket/detail";
    }

    @PostMapping("/{ticketId}/reply")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public String handleReplyTicket(
            @PathVariable Long ticketId,
            @RequestParam("content") String content,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            RedirectAttributes redirectAttributes) {

        Long responderId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketReplyRequest request = TicketReplyRequest.builder().content(content).build();
        ticketService.replyTicket(ticketId, request, responderId, files);

        redirectAttributes.addFlashAttribute("successMessage", "Phản hồi đã được ghi nhận thành công!");
        return "redirect:/tickets/detail/" + ticketId;
    }

    @PostMapping("/{ticketId}/close")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public String handleCloseTicket(
            @PathVariable Long ticketId,
            @RequestParam(value = "rating", required = false, defaultValue = "5") Integer rating,
            RedirectAttributes redirectAttributes) {

        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        ticketService.closeTicket(ticketId, currentUserId, rating);

        redirectAttributes.addFlashAttribute("successMessage", "Cảm ơn bạn đã đánh giá và xác nhận đóng yêu cầu!");
        return "redirect:/tickets/detail/" + ticketId;
    }
}
