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
    private String apiKey;

    @Value("${app.gemini.embedding-model:text-embedding-004}")
    private String embeddingModel;

    @Value("${app.gemini.chat-model:gemini-1.5-flash}")
    private String chatModel;

    public GeminiApiClient(ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().build();
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
            log.debug("[Gemini] API Key chưa được thiết lập, sinh vector giả lập cho môi trường dev/test");
            return generateDeterministicVector(text);
        }

        try {
            Map<String, Object> body = Map.of(
                    "model", "models/" + embeddingModel,
                    "content", Map.of("parts", List.of(Map.of("text", text)))
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
            log.warn("[Gemini] Không thể gọi Gemini Embedding API: {}. Sử dụng fallback vector.", e.getMessage());
        }

        return generateDeterministicVector(text);
    }

    /**
     * Gửi Prompt có kèm Context cho Gemini 1.5 Flash sinh câu trả lời
     */
    public String generateChatResponse(String systemPrompt, String userMessage) {
        if ("demo_key".equalsIgnoreCase(apiKey) || apiKey == null || apiKey.isBlank()) {
            log.debug("[Gemini] Chạy ở chế độ local không có key, trả về phản hồi mẫu.");
            return "Dựa vào quy chế học vụ được cung cấp: " + userMessage;
        }

        try {
            String fullPrompt = systemPrompt + "\n\n" + userMessage;
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", fullPrompt))))
            );

            String response = restClient.post()
                    .uri(CHAT_URL, chatModel, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode textNode = root.path("candidates").get(0).path("content").path("parts").get(0).path("text");

            if (!textNode.isMissingNode()) {
                return textNode.asText();
            }
        } catch (Exception e) {
            log.warn("[Gemini] Lỗi khi gọi Gemini Chat API: {}. Trả về fallback.", e.getMessage());
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
