package com.school.counseling.module.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.counseling.module.ai.dto.RagQueryRequest;
import com.school.counseling.module.ai.dto.RagQueryResponse;
import com.school.counseling.module.ai.service.RagChatbotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RagChatRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RagChatbotService ragChatbotService;

    @Test
    @DisplayName("TDD-CHAT-01: Gửi câu hỏi hợp lệ tới /api/v1/ai/chat -> 200 OK và trả về câu trả lời RAG")
    void askChatbot_validQuestion_returnsOk() throws Exception {
        RagQueryResponse mockResponse = RagQueryResponse.builder()
                .answer("Học phí hệ đại trà năm học 2026 khoảng 18,5 - 20,5 triệu/năm.")
                .primarySourceType("REGULATION")
                .needsHistoricalWarning(false)
                .confidenceScore(0.88)
                .build();

        when(ragChatbotService.ask(eq("học phí năm 2026"), any())).thenReturn(mockResponse);

        RagQueryRequest request = RagQueryRequest.builder()
                .question("học phí năm 2026")
                .build();

        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.answer").value("Học phí hệ đại trà năm học 2026 khoảng 18,5 - 20,5 triệu/năm."))
                .andExpect(jsonPath("$.data.primarySourceType").value("REGULATION"))
                .andExpect(jsonPath("$.data.needsHistoricalWarning").value(false));
    }

    @Test
    @DisplayName("TDD-CHAT-02: Gửi câu hỏi rỗng tới /api/v1/ai/chat -> 400 Bad Request vì vi phạm Validation")
    void askChatbot_blankQuestion_returnsBadRequest() throws Exception {
        RagQueryRequest request = RagQueryRequest.builder()
                .question("")
                .build();

        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("TDD-CHAT-03: Gửi câu hỏi kèm departmentId tới /api/v1/ai/chat -> Truyền đúng tham số và trả về 200 OK")
    void askChatbot_withDepartmentId_returnsOk() throws Exception {
        RagQueryResponse mockResponse = RagQueryResponse.builder()
                .answer("Chuẩn đầu ra tiếng Anh Khoa Ngoại ngữ là TOEIC 500.")
                .primarySourceType("FAQ_CHAT")
                .needsHistoricalWarning(true)
                .confidenceScore(0.72)
                .build();

        when(ragChatbotService.ask(eq("tiếng anh đầu ra"), eq(5L))).thenReturn(mockResponse);

        RagQueryRequest request = RagQueryRequest.builder()
                .question("tiếng anh đầu ra")
                .departmentId(5L)
                .build();

        mockMvc.perform(post("/api/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.needsHistoricalWarning").value(true));
    }
}
