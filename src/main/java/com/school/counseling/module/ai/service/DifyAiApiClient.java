package com.school.counseling.module.ai.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Client giao tiếp với Cloud Dify AI Engine (Ưu tiên số 1 trong Phễu cản tải RAG).
 *
 * Nhiệm vụ:
 *   - Gọi API Chatflow / Chatbot: POST https://api.dify.ai/v1/chat-messages
 *   - Gửi câu hỏi của sinh viên và nhận phản hồi tổng hợp từ Dify
 *   - Thiết lập Timeout ngắn (6 giây). Nếu Dify bị nghẽn (hết Quota, nghẽn mạng, lỗi 500/429)
 *     thì tự động trả về null để hệ thống kích hoạt Fallback xuống tầng Python Engine / Gemini Flash.
 */
@Slf4j
@Service
public class DifyAiApiClient {

    @Value("${app.dify.enabled:true}")
    private boolean difyEnabled;

    @Value("${app.dify.base-url:https://api.dify.ai/v1}")
    private String difyBaseUrl;

    @Value("${app.dify.api-key:app-Ku5Tbg0u2r7AD6ev5P6sQmlk}")
    private String difyApiKey;

    @Value("${app.dify.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${app.dify.read-timeout-ms:6000}")
    private int readTimeoutMs;

    private final ObjectMapper objectMapper;

    public DifyAiApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isDifyEnabled() {
        return difyEnabled && difyApiKey != null && !difyApiKey.isBlank() && !difyApiKey.equals("demo_key");
    }

    public record DifyChatRequest(
            Map<String, Object> inputs,
            String query,
            @JsonProperty("response_mode") String responseMode,
            String user,
            @JsonProperty("conversation_id") String conversationId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DifyRetrieverResource(
            int position,
            @JsonProperty("dataset_id") String datasetId,
            @JsonProperty("dataset_name") String datasetName,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("document_name") String documentName,
            @JsonProperty("segment_id") String segmentId,
            double score,
            String content
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DifyChatResponse(
            String event,
            @JsonProperty("task_id") String taskId,
            String id,
            @JsonProperty("message_id") String messageId,
            @JsonProperty("conversation_id") String conversationId,
            String mode,
            String answer,
            Map<String, Object> metadata,
            @JsonProperty("created_at") Long createdAt
    ) {}

    /**
     * Gửi câu hỏi đến Dify AI Chatflow.
     *
     * @param question       Câu hỏi của sinh viên
     * @param userIdentifier Định danh người dùng (MSSV hoặc guest)
     * @param conversationId ID phiên chat (nếu có để duy trì ngữ cảnh)
     * @return DifyChatResponse nếu thành công, null nếu Dify bị nghẽn/lỗi (để trigger Fallback)
     */
    public DifyChatResponse sendChatMessage(String question, String userIdentifier, String conversationId) {
        if (!isDifyEnabled()) {
            return null;
        }

        long tStart = System.currentTimeMillis();
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
            requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

            RestClient restClient = RestClient.builder()
                    .baseUrl(difyBaseUrl)
                    .requestFactory(requestFactory)
                    .defaultHeader("Authorization", "Bearer " + difyApiKey)
                    .defaultHeader("User-Agent", "QAUTE-Portal/1.0 (HCMUTE Counseling System)")
                    .build();

            DifyChatRequest requestBody = new DifyChatRequest(
                    Collections.emptyMap(),
                    question,
                    "blocking",
                    (userIdentifier != null && !userIdentifier.isBlank()) ? userIdentifier : "student-guest",
                    (conversationId != null) ? conversationId : ""
            );

            log.info("[Dify AI] 🚀 Đang gửi câu hỏi tới Dify Chatflow: '{}' (user={})",
                    question.length() > 60 ? question.substring(0, 60) + "..." : question,
                    requestBody.user());

            DifyChatResponse response = restClient.post()
                    .uri("/chat-messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(DifyChatResponse.class);

            long elapsed = System.currentTimeMillis() - tStart;

            if (response != null && response.answer() != null && !response.answer().isBlank()) {
                log.info("[Dify AI] ✅ Nhận phản hồi thành công sau {}ms (convId={})", elapsed, response.conversationId());
                return response;
            }

            log.warn("[Dify AI] ⚠️ Dify trả về response rỗng sau {}ms", elapsed);
            return null;

        } catch (RestClientException ex) {
            long elapsed = System.currentTimeMillis() - tStart;
            log.warn("[Dify AI] ⚠️ Dify bị nghẽn / timeout / lỗi sau {}ms: {}. Hệ thống sẽ kích hoạt Fallback xuống Gemini/Python Engine!",
                    elapsed, ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.error("[Dify AI] ❌ Lỗi không xác định khi gọi Dify: {}", ex.getMessage());
            return null;
        }
    }
}
