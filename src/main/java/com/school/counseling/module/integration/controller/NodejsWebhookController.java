/*
 * =========================================================================================
 * TÍNH NĂNG ĐÃ LOẠI BỎ THEO YÊU CẦU: MICROSERVICE NODE.JS VIDEO GENERATOR WEBHOOK
 * Toàn bộ mã nguồn dưới đây được chuyển về dạng chú thích (commented out) và vô hiệu hóa.
 * =========================================================================================
package com.school.counseling.module.integration.controller;

import com.school.counseling.common.dto.ApiResponse;
import com.school.counseling.module.feed.service.PostService;
import com.school.counseling.module.integration.dto.VideoWebhookPayload;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/integration")
@RequiredArgsConstructor
public class NodejsWebhookController {

    private final PostService postService;

    // Webhook nhận thông báo render video hoàn tất từ Node.js
    @PostMapping("/video-webhook")
    public ResponseEntity<ApiResponse<Void>> handleVideoWebhook(@Valid @RequestBody VideoWebhookPayload payload) {
        log.info("Đã nhận Webhook từ Node.js cho Post #{}, status: {}", payload.getPostId(), payload.getStatus());

        postService.handleVideoWebhookCallback(payload);

        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Webhook processed successfully")
                .build());
    }
}
*/

