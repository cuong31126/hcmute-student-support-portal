package com.school.counseling.module.ticket.controller;

import com.school.counseling.common.dto.ApiResponse;
import com.school.counseling.common.util.SecurityUtils;
import com.school.counseling.module.ticket.dto.CreateTicketRequest;
import com.school.counseling.module.ticket.dto.TicketReplyRequest;
import com.school.counseling.module.ticket.dto.TicketResponseDto;
import com.school.counseling.module.ticket.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class TicketRestController {

    private final TicketService ticketService;

    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<ApiResponse<TicketResponseDto>> createTicket(
            @Valid @ModelAttribute CreateTicketRequest request,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {

        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto response = ticketService.createTicket(request, currentUserId, files);
        return ResponseEntity.ok(ApiResponse.success(response, "Gửi yêu cầu tư vấn thành công"));
    }

    @PostMapping("/convert-chat/{conversationId}")
    public ResponseEntity<ApiResponse<TicketResponseDto>> convertChatToTicket(
            @PathVariable Long conversationId,
            @Valid @RequestBody CreateTicketRequest request) {

        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto response = ticketService.convertConversationToTicket(conversationId, request, currentUserId);
        return ResponseEntity.ok(ApiResponse.success(response, "Chuyển đổi hội thoại thành Ticket thành công"));
    }

    @GetMapping("/my-tickets")
    public ResponseEntity<ApiResponse<List<TicketResponseDto>>> getMyTickets() {
        Long currentUserId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new IllegalArgumentException("Người dùng chưa đăng nhập"));
        List<TicketResponseDto> tickets = ticketService.getMyTickets(currentUserId);
        return ResponseEntity.ok(ApiResponse.success(tickets));
    }

    @GetMapping("/{ticketId}")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public ResponseEntity<ApiResponse<TicketResponseDto>> getTicketDetail(@PathVariable Long ticketId) {
        TicketResponseDto ticket = ticketService.getTicketById(ticketId);
        return ResponseEntity.ok(ApiResponse.success(ticket));
    }

    @PostMapping(value = "/{ticketId}/reply", consumes = {"multipart/form-data"})
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public ResponseEntity<ApiResponse<TicketResponseDto>> replyTicket(
            @PathVariable Long ticketId,
            @Valid @ModelAttribute TicketReplyRequest request,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {

        Long responderId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto response = ticketService.replyTicket(ticketId, request, responderId, files);
        return ResponseEntity.ok(ApiResponse.success(response, "Gửi phản hồi thành công"));
    }

    @PostMapping("/{ticketId}/close")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public ResponseEntity<ApiResponse<TicketResponseDto>> closeTicket(
            @PathVariable Long ticketId,
            @RequestParam(value = "rating", required = false) Integer rating) {

        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto response = ticketService.closeTicket(ticketId, currentUserId, rating);
        return ResponseEntity.ok(ApiResponse.success(response, "Đóng yêu cầu tư vấn thành công"));
    }

    @GetMapping("/guest-track")
    public ResponseEntity<ApiResponse<TicketResponseDto>> trackGuestTicket(@RequestParam("token") String token) {
        TicketResponseDto ticket = ticketService.getTicketByGuestToken(token);
        return ResponseEntity.ok(ApiResponse.success(ticket, "Tra cứu tiến độ thành công"));
    }
}
