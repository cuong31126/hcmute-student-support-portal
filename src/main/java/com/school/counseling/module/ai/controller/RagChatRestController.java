package com.school.counseling.module.ai.controller;

import com.school.counseling.common.dto.ApiResponse;
import com.school.counseling.module.ai.dto.RagQueryRequest;
import com.school.counseling.module.ai.dto.RagQueryResponse;
import com.school.counseling.module.ai.service.RagChatbotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API Chatbot AI phục vụ sinh viên và người dùng đặt câu hỏi học vụ.
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class RagChatRestController {

    private final RagChatbotService ragChatbotService;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<RagQueryResponse>> askChatbot(
            @Valid @RequestBody RagQueryRequest request) {

        RagQueryResponse response = ragChatbotService.ask(request.getQuestion(), request.getDepartmentId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
