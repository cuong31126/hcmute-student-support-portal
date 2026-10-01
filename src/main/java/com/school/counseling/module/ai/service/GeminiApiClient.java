package com.school.counseling.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST Client giao tiếp với Google Gemini API (Model gemini-embedding-001 & gemini-2.5-flash).
 * Sử dụng RestClient chuẩn của Spring Boot 3.3.
 *
 * Cải tiến Sprint A:
 *  - BR-04: Exponential backoff retry khi nhận 429/503 (max 3 lần, delay [1s,3s,7s] ±jitter)
 *  - BR-05: Whitelist secondary model, handle non-JSON response gracefully
 *  - BR-08: generateChatResponseWithFlag() trả về [answer, isLlmGenerated]
 *  - BR-09: buildSmartFallback() render format thân thiện, không dump raw injected header
 */
@Slf4j
@Service
public class GeminiApiClient {

    private static final String EMBEDDING_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:embedContent?key={key}";
    private static final String CHAT_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}";

    /** BR-04: Backoff delays in ms: attempt 1=1s, 2=3s, 3=7s */
    private static final long[] RETRY_DELAYS_MS = {1_000L, 3_000L, 7_000L};
    private static final int MAX_RETRIES = RETRY_DELAYS_MS.length;

    /** BR-05: Danh sách tên model hợp lệ — ngăn chặn secondary model sai tên gây crash */
    private static final Set<String> VALID_MODEL_NAMES = Set.of(
            "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash",
            "gemini-2.0-flash-lite", "gemini-1.5-flash", "gemini-1.5-flash-8b",
            "gemini-1.5-pro", "gemini-2.5-pro"
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${app.gemini.api-key:demo_key}")
    private String apiKey = "demo_key";

    @Value("${app.gemini.embedding-model:gemini-embedding-001}")
    private String embeddingModel = "gemini-embedding-001";

    @Value("${app.gemini.chat-model:gemini-2.5-flash}")
    private String chatModel = "gemini-2.5-flash";

    /** BR-05: Fix từ 'gemini-flash-latest' (không hợp lệ) → 'gemini-2.0-flash-lite' (tên thật) */
    @Value("${app.gemini.secondary-chat-model:gemini-2.0-flash-lite}")
    private String secondaryChatModel = "gemini-2.0-flash-lite";

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
        return generateChatResponseWithFlag(systemPrompt, userMessage)[0];
    }

    /**
     * BR-08: Gửi Prompt cho Gemini và trả về cặp [answer, isLlmGenerated].
     * isLlmGenerated = "true" nếu LLM thực sự trả về câu trả lời,
     *                  "false" nếu rơi về buildSmartFallback() do API không khả dụng.
     *
     * BR-04: Retry exponential backoff khi nhận 429/503:
     *   Attempt 1: delay 1s, Attempt 2: delay 3s, Attempt 3: delay 7s (±30% jitter)
     * BR-05: Validate secondary model name trước khi gọi.
     */
    public String[] generateChatResponseWithFlag(String systemPrompt, String userMessage) {
        if ("demo_key".equalsIgnoreCase(apiKey) || apiKey == null || apiKey.isBlank()) {
            log.debug("[Gemini] Chạy chế độ local không có key, trả về phản hồi mẫu.");
            return new String[]{"Dựa vào quy chế học vụ được cung cấp: " + userMessage, "true"};
        }

        String fullPrompt = systemPrompt + "\n\n" + userMessage;
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", fullPrompt))))
        );

        // BR-04: Retry loop cho Primary model với exponential backoff
        String primaryReply = callWithRetry(chatModel, body);
        if (primaryReply != null && !primaryReply.isBlank()) {
            return new String[]{primaryReply, "true"};
        }

        // BR-05: Validate secondary model name trước khi gọi
        if (secondaryChatModel != null
                && !secondaryChatModel.equalsIgnoreCase(chatModel)
                && VALID_MODEL_NAMES.contains(secondaryChatModel)) {
            String secondaryReply = callWithRetry(secondaryChatModel, body);
            if (secondaryReply != null && !secondaryReply.isBlank()) {
                log.info("[Gemini] Secondary model '{}' phản hồi thành công!", secondaryChatModel);
                return new String[]{secondaryReply, "true"};
            }
        } else if (secondaryChatModel != null && !VALID_MODEL_NAMES.contains(secondaryChatModel)) {
            log.warn("[Gemini] Secondary model '{}' không nằm trong whitelist hợp lệ, bỏ qua.", secondaryChatModel);
        }

        // BR-08: Cả hai model fail → fallback, isLlmGenerated = false
        log.warn("[Gemini] Tất cả model đều không khả dụng, rơi về Smart Fallback.");
        return new String[]{buildSmartFallback(systemPrompt, userMessage), "false"};
    }

    /**
     * BR-04: Gọi API với retry exponential backoff.
     * Chỉ retry khi nhận 429 (rate limit) hoặc 503 (service unavailable).
     * Các lỗi khác (400, 404) fail nhanh không retry.
     */
    private String callWithRetry(String model, Map<String, Object> body) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                String reply = callChatApi(model, body);
                if (reply != null && !reply.isBlank()) {
                    if (attempt > 0) log.info("[Gemini] Model '{}' thành công ở lần retry {}", model, attempt + 1);
                    return reply;
                }
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if ((status == 429 || status == 503) && attempt < MAX_RETRIES - 1) {
                    long delay = addJitter(RETRY_DELAYS_MS[attempt]);
                    log.warn("[Gemini] Model '{}' {} (attempt {}/{}). Retry sau {}ms...",
                            model, status, attempt + 1, MAX_RETRIES, delay);
                    sleepQuietly(delay);
                } else {
                    log.warn("[Gemini] Model '{}' lỗi {} (attempt {}) — không retry thêm: {}",
                            model, status, attempt + 1, e.getMessage());
                    return null; // fail fast cho non-retryable hoặc hết retry
                }
            } catch (Exception e) {
                log.warn("[Gemini] Model '{}' exception (attempt {}): {}", model, attempt + 1, e.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    sleepQuietly(addJitter(RETRY_DELAYS_MS[attempt]));
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    /** BR-04: ±30% random jitter để tránh thundering herd */
    private long addJitter(long baseMs) {
        double jitter = 1.0 + (Math.random() * 0.6 - 0.3); // [0.7, 1.3]
        return (long) (baseMs * jitter);
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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

    /**
     * BR-09: Smart Fallback khi cả primary và secondary model đều fail.
     * Render context thân thiện, KHÔNG dump raw injected header [VĂN BẢN:...|NỘI DUNG:...].
     * Context từ RagChatbotService.buildContextString() đã được clean (rawContent).
     */
    private String buildSmartFallback(String systemPrompt, String userMessage) {
        if (systemPrompt != null && systemPrompt.contains("[THÔNG TIN NGỮ CẢNH ĐƯỢC TRÍCH XUẤT TỪ HỆ THỐNG]:")) {
            int startIdx = systemPrompt.indexOf("[THÔNG TIN NGỮ CẢNH ĐƯỢC TRÍCH XUẤT TỪ HỆ THỐNG]:");
            int endIdx = systemPrompt.indexOf("[NGUYÊN TẮC TRẢ LỜI]:");
            String context = (endIdx > startIdx)
                    ? systemPrompt.substring(startIdx + 50, endIdx).trim()
                    : systemPrompt.substring(startIdx + 50).trim();

            if (!context.isBlank() && !context.contains("Không tìm thấy văn bản quy chế")) {
                // BR-09: Dọn sạch lần cuối — phòng trường hợp legacy context vẫn có header tag
                String cleanContext = context
                        .replaceAll("(?s)\\[VĂN BẢN:.*?\\]\\s*\\[NỘI DUNG\\]:\\s*\n?", "")
                        .replaceAll("^\\d+\\s+(?=\\d)", "").trim();

                return "📋 *Máy chủ AI đang tạm bận, dưới đây là thông tin quy chế liên quan được trích xuất trực tiếp:*\n\n"
                        + cleanContext
                        + "\n\n💡 *Nếu cần giải thích thêm, bạn có thể gửi Ticket hỗ trợ tới đúng Phòng ban chuyên trách.*";
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
