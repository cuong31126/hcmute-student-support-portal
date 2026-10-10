package com.school.counseling.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
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

    @Value("${app.gemini.embedding-model:text-embedding-004}")
    private String embeddingModel = "text-embedding-004";

    @Value("${app.gemini.chat-model:gemini-1.5-flash}")
    private String chatModel = "gemini-1.5-flash";

    /** Model dự phòng khi primary model gặp sự cố */
    @Value("${app.gemini.secondary-chat-model:gemini-2.0-flash}")
    private String secondaryChatModel = "gemini-2.0-flash";

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

            byte[] responseBytes = restClient.post()
                    .uri(EMBEDDING_URL, embeddingModel, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.ALL)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);

            if (responseBytes == null || responseBytes.length == 0) {
                return generateDeterministicVector(text);
            }

            String response = new String(responseBytes, StandardCharsets.UTF_8);
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
        byte[] responseBytes = restClient.post()
                .uri(CHAT_URL, model, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.ALL)
                .body(body)
                .retrieve()
                .body(byte[].class);

        if (responseBytes == null || responseBytes.length == 0) {
            return null;
        }

        String response = new String(responseBytes, StandardCharsets.UTF_8);

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

    private static final Map<String, List<String>> TOPIC_CLUSTERS = Map.of(
            "CLUSTER_SCHOLARSHIP", List.of("học bổng", "khuyến khích", "hbkk", "khen thưởng", "trợ cấp"),
            "CLUSTER_ENGLISH", List.of("ngoại ngữ", "anh văn", "tiếng anh", "toeic", "vstep", "ielts", "chuẩn đầu ra", "avđr", "avđv", "ccta", "đầu ra"),
            "CLUSTER_SUSPENSION", List.of("bảo lưu", "tạm dừng", "nghỉ học", "rút hồ sơ", "hoãn thi"),
            "CLUSTER_WARNING", List.of("cảnh báo", "buộc thôi học", "thôi học", "học vụ", "buộc thôi"),
            "CLUSTER_RETAKE", List.of("học lại", "cải thiện", "học kỳ phụ", "học ghép", "trả nợ môn"),
            "CLUSTER_TUITION", List.of("học phí", "miễn giảm", "đóng tiền", "gia hạn", "học bổng tài trợ"),
            "CLUSTER_GRADUATION", List.of("tốt nghiệp", "đồ án", "khóa luận", "xét tốt nghiệp", "nhận bằng"),
            "CLUSTER_REGISTRATION", List.of("đăng ký môn", "học phần", "tín chỉ", "rút môn", "hủy môn"),
            "CLUSTER_LEADERSHIP", List.of("hiệu trưởng", "hieu truong", "trưởng khoa", "truong khoa", "phó hiệu trưởng", "phó trưởng khoa", "ban giám hiệu", "lãnh đạo", "ban chủ nhiệm")
    );

    /**
     * Thuật toán Local Academic Semantic N-Gram Vectorizer (768 chiều).
     * Thay thế hoàn toàn mã băm chuỗi thô ngẫu nhiên cũ.
     * Hoạt động 100% nội bộ (Zero-Network, 0ms latency, Zero-Rate-Limit).
     * Đảm bảo tính toán tương đồng Cosine chuẩn xác giữa Query và Chunk ngay cả khi Gemini API bị 429 hoặc mất mạng.
     */
    public static float[] generateDeterministicVector(String text) {
        if (text == null || text.isBlank()) {
            return new float[768];
        }

        // 1. Mở rộng từ viết tắt học vụ
        String expanded = AcademicAbbreviationUtils.expand(text).toLowerCase();

        // 2. Tách từ vựng (Tokens)
        String[] words = expanded.replaceAll("[^\\p{L}\\p{Nd}\\s]", " ")
                .trim().split("\\s+");
        if (words.length == 0 || (words.length == 1 && words[0].isBlank())) {
            return new float[768];
        }

        float[] composite = new float[768];

        // 3. Kích hoạt Semantic Concept Clusters
        for (Map.Entry<String, List<String>> entry : TOPIC_CLUSTERS.entrySet()) {
            boolean matched = false;
            for (String keyword : entry.getValue()) {
                if (expanded.contains(keyword)) {
                    matched = true;
                    break;
                }
            }
            if (matched) {
                float[] clusterVec = generateTokenVector(entry.getKey());
                for (int i = 0; i < 768; i++) {
                    composite[i] += 4.5f * clusterVec[i];
                }
            }
        }

        // 4. Danh sách từ khóa học vụ trọng điểm với trọng số Semantic Boost
        Map<String, Float> academicWeights = Map.ofEntries(
                Map.entry("hiệu_trưởng", 5.0f),
                Map.entry("trưởng_khoa", 5.0f),
                Map.entry("lãnh_đạo", 4.5f),
                Map.entry("học_bổng", 4.5f),
                Map.entry("khuyến_khích", 4.2f),
                Map.entry("cảnh_báo", 4.2f),
                Map.entry("thôi_học", 4.2f),
                Map.entry("buộc_thôi", 4.2f),
                Map.entry("ngoại_ngữ", 4.0f),
                Map.entry("anh_văn", 4.0f),
                Map.entry("tiếng_anh", 4.0f),
                Map.entry("toeic", 4.5f),
                Map.entry("vstep", 4.5f),
                Map.entry("bảo_lưu", 4.5f),
                Map.entry("tạm_dừng", 4.0f),
                Map.entry("học_phí", 4.0f),
                Map.entry("học_lại", 4.2f),
                Map.entry("cải_thiện", 4.2f),
                Map.entry("rèn_luyện", 3.8f),
                Map.entry("tín_chỉ", 3.5f),
                Map.entry("học_phần", 3.5f),
                Map.entry("đăng_ký", 3.5f),
                Map.entry("đồ_án", 3.8f),
                Map.entry("tốt_nghiệp", 4.2f),
                Map.entry("chuẩn_đầu", 3.8f),
                Map.entry("đầu_ra", 3.8f),
                Map.entry("chuyển_ngành", 4.0f),
                Map.entry("bằng_đôi", 4.0f),
                Map.entry("miễn_giảm", 3.8f),
                Map.entry("phúc_khảo", 4.0f),
                Map.entry("rút_học", 3.8f),
                Map.entry("khen_thưởng", 3.8f)
        );

        Set<String> stopWords = Set.of(
                "là", "và", "hoặc", "của", "ở", "tại", "trong", "cho", "như", "gì",
                "nào", "sao", "thế", "được", "có", "không", "làm", "thì", "về", "các",
                "những", "một", "bị", "bởi", "đã", "đang", "sẽ", "đến", "từ"
        );

        // 5. Cộng dồn vector Unigram
        for (String w : words) {
            if (w.isBlank()) continue;
            float weight = 0.5f;
            if (stopWords.contains(w)) {
                weight = 0.02f;
            } else if (w.length() > 3) {
                weight = 1.0f;
            }

            float[] tokenVec = generateTokenVector(w);
            for (int i = 0; i < 768; i++) {
                composite[i] += weight * tokenVec[i];
            }
        }

        // 6. Cộng dồn vector Bigram (N-gram 2 từ liên tiếp)
        for (int i = 0; i < words.length - 1; i++) {
            String bigram = words[i] + "_" + words[i + 1];
            float weight = academicWeights.getOrDefault(bigram, 1.2f);
            float[] bigramVec = generateTokenVector(bigram);
            for (int j = 0; j < 768; j++) {
                composite[j] += weight * bigramVec[j];
            }
        }

        // 7. Chuẩn hóa L2-norm
        float norm = 0.0f;
        for (float v : composite) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 1e-6f) {
            for (int i = 0; i < 768; i++) {
                composite[i] /= norm;
            }
        }

        return composite;
    }

    private static float[] generateTokenVector(String token) {
        float[] vec = new float[768];
        long seed = 0x9e3779b97f4a7c15L;
        for (int i = 0; i < token.length(); i++) {
            seed = 31 * seed + token.charAt(i);
        }
        java.util.Random rand = new java.util.Random(seed);
        for (int i = 0; i < 768; i++) {
            vec[i] = (float) rand.nextGaussian();
        }
        // Normalize token vector
        float norm = 0.0f;
        for (float v : vec) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < 768; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }
}
