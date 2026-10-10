package com.school.counseling.module.ticket.controller;

import com.school.counseling.common.dto.ApiResponse;
import com.school.counseling.common.util.SecurityUtils;
import com.school.counseling.module.ticket.dto.CreateTicketRequest;
import com.school.counseling.module.ticket.dto.TicketReplyRequest;
import com.school.counseling.module.ticket.dto.TicketResponseDto;
import com.school.counseling.module.ticket.dto.TicketSummaryDto;
import com.school.counseling.module.ticket.dto.TicketTriageResponseDto;
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

    public record TriageRequest(String title, String description) {}

    /**
     * Endpoint AI Triage: Phân tích tiêu đề và nội dung để gợi ý Đơn vị và Mức độ ưu tiên.
     */
    @PostMapping("/ai-triage")
    public ResponseEntity<ApiResponse<TicketTriageResponseDto>> triageTicket(
            @RequestBody(required = false) TriageRequest body,
            @RequestParam(value = "title", required = false) String titleParam,
            @RequestParam(value = "description", required = false) String descParam) {

        String title = (body != null && body.title() != null) ? body.title() : (titleParam != null ? titleParam : "");
        String description = (body != null && body.description() != null) ? body.description() : (descParam != null ? descParam : "");

        TicketTriageResponseDto triage = ticketService.suggestTicketTriage(title, description);
        return ResponseEntity.ok(ApiResponse.success(triage, "Phân loại và định tuyến Ticket thành công"));
    }

    @PostMapping(consumes = {"multipart/form-data"})
    public ResponseEntity<ApiResponse<TicketResponseDto>> createTicket(
            @Valid @ModelAttribute CreateTicketRequest request,
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {

        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto response = ticketService.createTicket(request, currentUserId, files);
        return ResponseEntity.ok(ApiResponse.success(response, "Gửi yêu cầu tư vấn thành công"));
    }

    /**
     * @deprecated Endpoint chuyển đổi Conversation thành Ticket (Prototype - Hiện chưa có UI gọi tới).
     * Giao diện hiện tại chuyển đổi qua Form trực tiếp: /tickets/create?title=...
     * Xem chi tiết tại: docs/requirements/chat_module_and_ticketing_architecture_guide.md
     */
    @Deprecated
    @PostMapping("/convert-chat/{conversationId}")
    public ResponseEntity<ApiResponse<TicketResponseDto>> convertChatToTicket(
            @PathVariable Long conversationId,
            @Valid @RequestBody CreateTicketRequest request) {

        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto response = ticketService.convertConversationToTicket(conversationId, request, currentUserId);
        return ResponseEntity.ok(ApiResponse.success(response, "Chuyển đổi hội thoại thành Ticket thành công"));
    }

    @GetMapping("/my-tickets")
    public ResponseEntity<ApiResponse<List<TicketSummaryDto>>> getMyTickets() {
        Long currentUserId = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new IllegalArgumentException("Người dùng chưa đăng nhập"));
        List<TicketSummaryDto> tickets = ticketService.getMyTicketSummaries(currentUserId);
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
            @RequestParam(value = "files", required = false) List<MultipartFile> files) {

        boolean hasContent = request != null && request.getContent() != null && !request.getContent().trim().isEmpty();
        boolean hasFiles = files != null && !files.isEmpty() && files.stream().anyMatch(f -> f != null && !f.isEmpty());

        if (!hasContent && !hasFiles) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Vui lòng nhập nội dung hoặc đính kèm tệp tin/hình ảnh"));
        }

        Long responderId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto response = ticketService.replyTicket(ticketId, request, responderId, files);
        return ResponseEntity.ok(ApiResponse.success(response, "Gửi phản hồi thành công"));
    }

    @PostMapping("/{ticketId}/close")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public ResponseEntity<ApiResponse<TicketResponseDto>> closeTicket(
            @PathVariable Long ticketId,
            @RequestParam(value = "rating", required = false) Integer rating,
            @RequestParam(value = "feedback", required = false) String feedback) {

        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto response = ticketService.closeTicket(ticketId, currentUserId, rating, feedback);
        return ResponseEntity.ok(ApiResponse.success(response, "Đóng yêu cầu tư vấn thành công"));
    }

    @PostMapping("/{ticketId}/rate")
    @PreAuthorize("@deptSecurity.canAccessTicket(#ticketId)")
    public ResponseEntity<ApiResponse<TicketResponseDto>> rateTicket(
            @PathVariable Long ticketId,
            @RequestParam(value = "rating") Integer rating,
            @RequestParam(value = "feedback", required = false) String feedback) {

        Long currentUserId = SecurityUtils.getCurrentUserId().orElse(null);
        TicketResponseDto response = ticketService.rateTicket(ticketId, currentUserId, rating, feedback);
        return ResponseEntity.ok(ApiResponse.success(response, "Ghi nhận đánh giá CSAT thành công"));
    }

    @GetMapping("/guest-track")
    public ResponseEntity<ApiResponse<TicketResponseDto>> trackGuestTicket(@RequestParam("token") String token) {
        TicketResponseDto ticket = ticketService.getTicketByGuestToken(token);
        return ResponseEntity.ok(ApiResponse.success(ticket, "Tra cứu tiến độ thành công"));
    }
}
