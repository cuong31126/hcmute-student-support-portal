package com.school.counseling.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import org.springframework.test.web.client.ExpectedCount;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;

/**
 * Reproduction Test: Chứng minh lỗi khi Google Gemini trả về 503 Service Unavailable (High Demand)
 * Code hiện tại có cơ chế retry / secondary model fallback.
 */
class GeminiApiClientReproductionTest {

    @Test
    @DisplayName("Reproduction: Khi Google Gemini trả về 503 High Demand, hệ thống phải tự động fallback sang model dự phòng thành công")
    void shouldRecoverWhenPrimaryModelExperiencesHighDemand503() {
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        GeminiApiClient client = new GeminiApiClient(objectMapper);
        // Inject mock RestClient và API Key
        ReflectionTestUtils.setField(client, "restClient", restClientBuilder.build());
        ReflectionTestUtils.setField(client, "apiKey", "test-api-key-123");
        ReflectionTestUtils.setField(client, "chatModel", "gemini-2.5-flash");

        String error503Json = """
                {
                  "error": {
                    "code": 503,
                    "message": "This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.",
                    "status": "UNAVAILABLE"
                  }
                }
                """;

        // Model chính gemini-2.5-flash bị 503 (sẽ retry 3 lần)
        mockServer.expect(ExpectedCount.times(3), requestTo(containsString("gemini-2.5-flash:generateContent")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(error503Json));

        // Lần sau (Kỳ vọng): Hệ thống tự động fallback gọi sang model dự phòng gemini-2.0-flash-lite và thành công
        String successJson = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "Sinh viên cần đạt từ 8.0 trở lên để nhận học bổng."
                          }
                        ]
                      }
                    }
                  ]
                }
                """;
        mockServer.expect(requestTo(containsString("gemini-2.0-flash-lite:generateContent")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(successJson, MediaType.APPLICATION_JSON));

        // Thực thi gọi hàm
        String response = client.generateChatResponse("System context", "Học bổng cần mấy điểm?");

        // Verification
        mockServer.verify();

        // Kiểm tra kết quả: KHÔNG ĐƯỢC trả về thông báo bảo trì, mà phải trả về câu trả lời hợp lệ từ model dự phòng
        assertNotEquals("Hệ thống AI đang bảo trì kết nối ngoài. Vui lòng liên hệ trực tiếp phòng ban phụ trách để được giải đáp.", response);
        assertTrue(response.contains("học bổng"));
    }

    @Test
    @DisplayName("Smart Fallback: Khi tất cả model bên ngoài đều lỗi, hệ thống phải trích xuất điều khoản từ ngữ cảnh RAG")
    void shouldFallbackToSmartGroundedContextWhenAllModelsFail() {
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        GeminiApiClient client = new GeminiApiClient(objectMapper);
        ReflectionTestUtils.setField(client, "restClient", restClientBuilder.build());
        ReflectionTestUtils.setField(client, "apiKey", "test-api-key-123");

        // Cả 2 model đều trả về lỗi 503 (retry 3 lần mỗi model)
        mockServer.expect(ExpectedCount.times(3), requestTo(containsString("gemini-2.5-flash:generateContent")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        mockServer.expect(ExpectedCount.times(3), requestTo(containsString("gemini-2.0-flash-lite:generateContent")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        String ragSystemPrompt = """
                Bạn là Cố vấn Học vụ HCMUTE.
                [THÔNG TIN NGỮ CẢNH ĐƯỢC TRÍCH XUẤT TỪ HỆ THỐNG]:
                - Tiêu đề: Quyết định học bổng 2026
                  Nội dung: Điều 5. Sinh viên có điểm rèn luyện từ 80 và CPA từ 3.2 được xét học bổng khuyến khích.
                
                [NGUYÊN TẮC TRẢ LỜI]:
                1. Trả lời chuẩn mực.
                """;

        String response = client.generateChatResponse(ragSystemPrompt, "Điều kiện học bổng?");

        mockServer.verify();
        assertNotNull(response);
        assertTrue(response.contains("Điều 5. Sinh viên có điểm rèn luyện từ 80"), "Phải giữ lại trích đoạn quy chế cho sinh viên");
        assertTrue(response.contains("Máy chủ AI đang tạm bận"), "Phải có thông báo giải thích thân thiện");
    }
}
