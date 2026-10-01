package com.school.counseling.module.ai.controller;

import com.school.counseling.common.dto.ApiResponse;
import com.school.counseling.module.ai.dto.RagQueryRequest;
import com.school.counseling.module.ai.dto.RagQueryResponse;
import com.school.counseling.module.ai.service.RagChatbotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * REST API Chatbot AI phục vụ sinh viên đặt câu hỏi học vụ.
 *
 * Cải tiến quan trọng: Chuyển từ đồng bộ (Blocking) sang Bất đồng bộ (Non-Blocking):
 * - Thay vì gọi trực tiếp ragChatbotService.ask() (block Tomcat worker thread 1-3s),
 *   controller trả về CompletableFuture và ngay lập tức giải phóng Tomcat worker thread.
 * - Tác vụ AI được xử lý trong aiTaskExecutor (pool riêng: core=10, max=30).
 * - Hard Timeout 8 giây: nếu Gemini không phản hồi → trả về Fallback response thân thiện.
 * - DeferredResult timeout 9 giây: bảo vệ tầng HTTP Servlet.
 *
 * Hệ quả: 100 SV hỏi AI cùng lúc không còn làm nghẽn 200 Tomcat worker threads.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class RagChatRestController {

    private final RagChatbotService ragChatbotService;
    private final com.school.counseling.module.ai.service.RagKnowledgeService ragKnowledgeService;

    private static final long DEFERRED_TIMEOUT_MS = 15_000L;  // 15s timeout cho DeferredResult HTTP
    private static final String FALLBACK_MESSAGE =
            "Trợ lý AI đang xử lý lượng lớn yêu cầu hoặc đang bảo trì. " +
            "Bạn có thể tra cứu trong mục FAQ, hoặc bấm 'Gửi Ticket Hỗ Trợ' để Cán bộ Phòng/Khoa phụ trách liên hệ lại sớm nhất.";

    /**
     * API nhận câu hỏi học vụ từ sinh viên và trả về câu trả lời từ hệ thống RAG.
     */
    @PostMapping("/chat")
    public DeferredResult<ResponseEntity<ApiResponse<RagQueryResponse>>> askChatbot(
            @Valid @RequestBody RagQueryRequest request) {

        DeferredResult<ResponseEntity<ApiResponse<RagQueryResponse>>> deferredResult =
                new DeferredResult<>(DEFERRED_TIMEOUT_MS, buildFallbackResponse());

        // Timeout handler: Gemini không phản hồi trong 15s → trả về Fallback
        deferredResult.onTimeout(() -> {
            log.warn("[AI Timeout] Cau hoi vuot qua {}ms: '{}'", DEFERRED_TIMEOUT_MS,
                    request.getQuestion() != null ? request.getQuestion().substring(0, Math.min(50, request.getQuestion().length())) : "");
            deferredResult.setErrorResult(buildFallbackResponse());
        });

        // Giao tác vụ cho aiTaskExecutor (non-blocking): Tomcat worker được giải phóng ngay
        CompletableFuture.supplyAsync(
                () -> ragChatbotService.ask(request.getQuestion(), request.getDepartmentId())
        ).orTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
         .whenComplete((response, ex) -> {
             if (ex != null) {
                 if (ex instanceof TimeoutException) {
                     log.warn("[AI Timeout CompletableFuture] Gemini API khong phan hoi sau 12s, kich hoat Fallback.");
                 } else {
                     log.error("[AI Error] Loi xu ly cau hoi AI: {}", ex.getMessage(), ex);
                 }
                 deferredResult.setResult(buildFallbackResponse());
             } else {
                 deferredResult.setResult(ResponseEntity.ok(ApiResponse.success(response)));
             }
         });

        return deferredResult;
    }

    /**
     * API Human-in-the-loop: Cán bộ chuyển đổi Ticket đã giải quyết thành FAQ Tri thức AI
     */
    @PostMapping("/promote-ticket-to-faq")
    public ResponseEntity<ApiResponse<Map<String, Object>>> promoteTicketToFaq(
            @RequestBody Map<String, Long> payload) {
        Long ticketId = payload.get("ticketId");
        if (ticketId == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Thiếu mã Ticket ID"));
        }
        try {
            var chunk = ragKnowledgeService.promoteTicketToFaq(ticketId);
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("chunkId", chunk.getId(), "title", chunk.getTitle()),
                    "Đã nạp thành công câu hỏi và câu trả lời vào Kho Tri Thức AI!"
            ));
        } catch (Exception e) {
            log.error("[AI FAQ Promote] That bai khi nap Ticket #{}: {}", ticketId, e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Không thể nạp vào FAQ: " + e.getMessage()));
        }
    }

    private ResponseEntity<ApiResponse<RagQueryResponse>> buildFallbackResponse() {
        RagQueryResponse fallback = RagQueryResponse.builder()
                .answer(FALLBACK_MESSAGE)
                .suggestCreateTicket(true)
                .build();
        return ResponseEntity.ok(ApiResponse.success(fallback));
    }
}
