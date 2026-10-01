package com.school.counseling.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST Client giao tiếp với Google Gemini API (Model text-embedding-004 & gemini-1.5-flash).
 * Sử dụng RestClient chuẩn của Spring Boot 3.3.
 */
@Slf4j
@Service
public class GeminiApiClient {

    private static final String EMBEDDING_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:embedContent?key={key}";
    private static final String CHAT_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${app.gemini.api-key:demo_key}")
    private String apiKey = "demo_key";

    @Value("${app.gemini.embedding-model:gemini-embedding-001}")
    private String embeddingModel = "gemini-embedding-001";

    @Value("${app.gemini.chat-model:gemini-2.5-flash}")
    private String chatModel = "gemini-2.5-flash";

    @Value("${app.gemini.secondary-chat-model:gemini-flash-latest}")
    private String secondaryChatModel = "gemini-flash-latest";

    public GeminiApiClient(ObjectMapper objectMapper) {
        org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(10));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Tạo vector embedding 768 chiều từ một đoạn văn bản
     */
    public float[] getEmbedding(String text) {
        if (text == null || text.isBlank()) {
            return new float[768];
        }

        if ("demo_key".equalsIgnoreCase(apiKey) || apiKey == null || apiKey.isBlank()) {
            log.debug("[Gemini] API Key chua duoc thiet lap, sinh vector gia lap cho moi truong dev/test");
            return generateDeterministicVector(text);
        }

        try {
            Map<String, Object> body = Map.of(
                    "model", "models/" + embeddingModel,
                    "content", Map.of("parts", List.of(Map.of("text", text))),
                    "outputDimensionality", 768
            );

            String response = restClient.post()
                    .uri(EMBEDDING_URL, embeddingModel, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode valuesNode = root.path("embedding").path("values");

            if (valuesNode.isArray() && valuesNode.size() > 0) {
                float[] result = new float[valuesNode.size()];
                for (int i = 0; i < valuesNode.size(); i++) {
                    result[i] = (float) valuesNode.get(i).asDouble();
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("[Gemini] Khong the goi Gemini Embedding API: {}. Su dung fallback vector.", e.getMessage());
        }

        return generateDeterministicVector(text);
    }

    /**
     * Gửi Prompt có kèm Context cho Gemini sinh câu trả lời
     * Tích hợp cơ chế Fallback tự động sang Secondary Model khi Model chính bị 503 / 429
     */
    public String generateChatResponse(String systemPrompt, String userMessage) {
        if ("demo_key".equalsIgnoreCase(apiKey) || apiKey == null || apiKey.isBlank()) {
            log.debug("[Gemini] Chay o che do local khong co key, tra ve phan hoi mau.");
            return "Dựa vào quy chế học vụ được cung cấp: " + userMessage;
        }

        try {
            String fullPrompt = systemPrompt + "\n\n" + userMessage;
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", fullPrompt))))
            );

            // 1. Thử gọi Model chính (ví dụ gemini-2.5-flash)
            try {
                String reply = callChatApi(chatModel, body);
                if (reply != null && !reply.isBlank()) {
                    return reply;
                }
            } catch (Exception e) {
                log.warn("[Gemini] Model chinh '{}' gap su co ({}). Tu dong chuyen sang model du phong '{}'...",
                        chatModel, e.getMessage(), secondaryChatModel);
            }

            // 2. Tự động chuyển tiếp sang Secondary Model dự phòng (ví dụ gemini-flash-latest)
            if (secondaryChatModel != null && !secondaryChatModel.equalsIgnoreCase(chatModel)) {
                try {
                    String reply = callChatApi(secondaryChatModel, body);
                    if (reply != null && !reply.isBlank()) {
                        log.info("[Gemini] Model du phong '{}' da phan hoi thanh cong!", secondaryChatModel);
                        return reply;
                    }
                } catch (Exception e) {
                    log.warn("[Gemini] Model du phong '{}' cung gap su co: {}", secondaryChatModel, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[Gemini] Loi khi xu ly chat: {}", e.getMessage());
        }

        // 3. Fallback thông minh dựa trên Context RAG nếu các model bên ngoài đều không khả dụng
        return buildSmartFallback(systemPrompt, userMessage);
    }

    private String callChatApi(String model, Map<String, Object> body) {
        String response = restClient.post()
                .uri(CHAT_URL, model, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && parts.size() > 0) {
                    return parts.get(0).path("text").asText("");
                }
            }
        } catch (Exception e) {
            log.warn("[Gemini] Parse JSON response that bai: {}", e.getMessage());
        }
        return null;
    }

    private String buildSmartFallback(String systemPrompt, String userMessage) {
        if (systemPrompt != null && systemPrompt.contains("[THÔNG TIN NGỮ CẢNH ĐƯỢC TRÍCH XUẤT TỪ HỆ THỐNG]:")) {
            int startIdx = systemPrompt.indexOf("[THÔNG TIN NGỮ CẢNH ĐƯỢC TRÍCH XUẤT TỪ HỆ THỐNG]:");
            int endIdx = systemPrompt.indexOf("[NGUYÊN TẮC TRẢ LỜI]:");
            String context = (endIdx > startIdx)
                    ? systemPrompt.substring(startIdx + 50, endIdx).trim()
                    : systemPrompt.substring(startIdx + 50).trim();

            if (!context.isBlank() && !context.contains("Không tìm thấy văn bản quy chế")) {
                return "ℹ️ *Do máy chủ AI đang quá tải đột biến, hệ thống tự động trích xuất thông tin quy chế liên quan gửi trực tiếp tới bạn:*\n\n"
                        + context
                        + "\n\n💡 *Nếu cần hướng dẫn thêm, bạn có thể gửi Ticket hỗ trợ tới đúng Phòng ban chuyên trách.*";
            }
        }
        return "Hệ thống AI đang bảo trì kết nối ngoài. Vui lòng liên hệ trực tiếp phòng ban phụ trách để được giải đáp.";
    }

    /**
     * Thuật toán sinh vector giả lập xác định (deterministic) 768 chiều khi chạy test hoặc không có API key
     */
    private float[] generateDeterministicVector(String text) {
        float[] vector = new float[768];
        int hash = text.hashCode();
        for (int i = 0; i < 768; i++) {
            vector[i] = (float) Math.sin(hash + i * 31);
        }
        // Normalize vector
        float norm = 0.0f;
        for (float v : vector) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < 768; i++) vector[i] /= norm;
        }
        return vector;
    }
}
