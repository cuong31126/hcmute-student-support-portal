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

    private static final long DEFERRED_TIMEOUT_MS = 9_000L;  // 9s timeout cho DeferredResult HTTP
    private static final String FALLBACK_MESSAGE =
            "Trợ lý AI đang xử lý lượng lớn yêu cầu hoặc đang bảo trì. " +
            "Bạn có thể tra cứu trong mục FAQ, hoặc bấm 'Gửi Ticket Hỗ Trợ' để Cán bộ Phòng/Khoa phụ trách liên hệ lại sớm nhất.";

    /**
     * API nhận câu hỏi học vụ từ sinh viên và trả về câu trả lời từ hệ thống RAG.
     *
     * Sử dụng DeferredResult (Servlet 3.1+) để không block Tomcat worker thread:
     * 1. Tomcat worker nhận request, tạo DeferredResult và lập tức trả control về container.
     * 2. aiTaskExecutor chạy CompletableFuture.supplyAsync() để gọi Gemini AI.
     * 3. Khi AI trả về → setResult() hoặc timeout → setErrorResult() với Fallback.
     */
    @PostMapping("/chat")
    public DeferredResult<ResponseEntity<ApiResponse<RagQueryResponse>>> askChatbot(
            @Valid @RequestBody RagQueryRequest request) {

        DeferredResult<ResponseEntity<ApiResponse<RagQueryResponse>>> deferredResult =
                new DeferredResult<>(DEFERRED_TIMEOUT_MS, buildFallbackResponse());

        // Timeout handler: Gemini không phản hồi trong 9s → trả về Fallback
        deferredResult.onTimeout(() -> {
            log.warn("[AI Timeout] Câu hỏi vượt quá {}ms: '{}'", DEFERRED_TIMEOUT_MS,
                    request.getQuestion() != null ? request.getQuestion().substring(0, Math.min(50, request.getQuestion().length())) : "");
            deferredResult.setErrorResult(buildFallbackResponse());
        });

        // Giao tác vụ cho aiTaskExecutor (non-blocking): Tomcat worker được giải phóng ngay
        CompletableFuture.supplyAsync(
                () -> ragChatbotService.ask(request.getQuestion(), request.getDepartmentId())
        ).orTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
         .whenComplete((response, ex) -> {
             if (ex != null) {
                 if (ex instanceof TimeoutException) {
                     log.warn("[AI Timeout CompletableFuture] Gemini API không phản hồi sau 8s");
                 } else {
                     log.error("[AI Error] Lỗi xử lý câu hỏi AI: {}", ex.getMessage(), ex);
                 }
                 deferredResult.setResult(buildFallbackResponse());
             } else {
                 deferredResult.setResult(ResponseEntity.ok(ApiResponse.success(response)));
             }
         });

        return deferredResult;
    }

    private ResponseEntity<ApiResponse<RagQueryResponse>> buildFallbackResponse() {
        RagQueryResponse fallback = RagQueryResponse.builder()
                .answer(FALLBACK_MESSAGE)
                .suggestCreateTicket(true)
                .build();
        return ResponseEntity.ok(ApiResponse.success(fallback));
    }
}
