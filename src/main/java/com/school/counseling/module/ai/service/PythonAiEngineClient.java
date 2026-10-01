package com.school.counseling.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;

/**
 * Client giao tiếp với Python AI Engine (FastAPI cổng 8001).
 *
 * Đây là "cầu nối" giữa Spring Boot và Python lõi AI:
 *   - Spring Boot nhận request từ sinh viên (cổng 8080)
 *   - Gọi Python Engine qua HTTP nội bộ (cổng 8001)
 *   - Python Engine chạy RAG ChromaDB + Gemini + Evaluator
 *   - Trả kết quả về cho Spring Boot
 *
 * Cơ chế Fallback an toàn:
 *   - Nếu Python Engine không chạy hoặc timeout → trả null
 *   - RagChatbotService sẽ fallback về Java RAG (tương thích ngược)
 */
@Slf4j
@Service
public class PythonAiEngineClient {

    /** URL base của Python AI Engine */
    @Value("${app.python-ai.base-url:http://127.0.0.1:8001}")
    private String pythonBaseUrl;

    /** true = gọi Python Engine; false = dùng Java RAG (legacy mode) */
    @Value("${app.python-ai.enabled:false}")
    private boolean pythonAiEnabled;

    /** Timeout kết nối đến Python Engine (ms) */
    @Value("${app.python-ai.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    /** Timeout đọc response từ Python Engine (ms) */
    @Value("${app.python-ai.read-timeout-ms:20000}")
    private int readTimeoutMs;

    private final ObjectMapper objectMapper;

    public PythonAiEngineClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * DTO nội bộ cho request gửi sang Python.
     */
    public record PythonChatRequest(String question, Long department_id, boolean evaluate) {}

    /**
     * DTO nội bộ cho response nhận từ Python.
     * Các field tương thích với ChatResponse Pydantic model của FastAPI.
     */
    public record PythonChatResponse(
            String reply,
            String source_type,
            double confidence_score,
            boolean suggest_create_ticket,
            boolean llm_generated,
            int execution_time_ms,
            boolean needs_historical_warning,
            Integer faithfulness_score,
            Integer context_relevance_score,
            Integer answer_relevance_score,
            String eval_review
    ) {}

    /**
     * Gọi Python AI Engine để trả lời câu hỏi học vụ.
     *
     * @param question      Câu hỏi của sinh viên
     * @param departmentId  ID phòng ban (có thể null)
     * @param withEvaluation true nếu muốn Python tự chấm điểm Faithfulness
     * @return PythonChatResponse hoặc null nếu Python Engine không khả dụng
     */
    public PythonChatResponse askPythonEngine(String question, Long departmentId, boolean withEvaluation) {
        if (!pythonAiEnabled) {
            log.debug("[PythonAI] Python Engine bị tắt (app.python-ai.enabled=false). Dùng Java RAG.");
            return null;
        }

        try {
            var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
            requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

            var client = RestClient.builder()
                    .baseUrl(pythonBaseUrl)
                    .requestFactory(requestFactory)
                    .build();

            var requestBody = new PythonChatRequest(question, departmentId, withEvaluation);

            String rawJson = client.post()
                    .uri("/ai/ask")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            PythonChatResponse response = objectMapper.readValue(rawJson, PythonChatResponse.class);
            log.info("[PythonAI] ✅ Trả lời thành công: confidence={:.3f}, llm={}, ticket={}",
                    response.confidence_score(), response.llm_generated(), response.suggest_create_ticket());
            return response;

        } catch (RestClientException e) {
            log.warn("[PythonAI] ⚠️ Python Engine không khả dụng ({}). Fallback Java RAG.", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("[PythonAI] ❌ Lỗi không mong đợi khi gọi Python: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Kiểm tra Python AI Engine còn sống không.
     * Spring Boot gọi định kỳ hoặc khi startup.
     */
    public boolean isHealthy() {
        if (!pythonAiEnabled) return false;
        try {
            var client = RestClient.builder()
                    .baseUrl(pythonBaseUrl)
                    .build();
            String health = client.get()
                    .uri("/health")
                    .retrieve()
                    .body(String.class);
            return health != null && health.contains("\"status\":\"ok\"");
        } catch (Exception e) {
            log.debug("[PythonAI] Health check failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean isPythonAiEnabled() {
        return pythonAiEnabled;
    }

    public String getPythonBaseUrl() {
        return pythonBaseUrl;
    }
}
