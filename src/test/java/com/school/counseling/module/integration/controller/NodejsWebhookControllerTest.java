package com.school.counseling.module.integration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.counseling.module.feed.service.PostService;
import com.school.counseling.module.integration.dto.VideoWebhookPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class NodejsWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PostService postService;

    @Test
    @DisplayName("TDD-04: API Webhook nhận payload hợp lệ -> Trả về HTTP 200 OK")
    void shouldAcceptVideoWebhookAndReturn200() throws Exception {
        VideoWebhookPayload payload = new VideoWebhookPayload();
        payload.setPostId(100L);
        payload.setVideoUrl("http://localhost:5000/videos/post_100/video.mp4");
        payload.setDurationSeconds(40);
        payload.setStatus("COMPLETED");

        doNothing().when(postService).handleVideoWebhookCallback(any(VideoWebhookPayload.class));

        mockMvc.perform(post("/api/v1/integration/video-webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Webhook processed successfully"));
    }

    @Test
    @DisplayName("TDD-05: API Webhook thiếu trường bắt buộc postId -> Trả về HTTP 400 Bad Request")
    void shouldRejectInvalidWebhookPayloadWith400() throws Exception {
        VideoWebhookPayload invalidPayload = new VideoWebhookPayload(); // postId = null

        mockMvc.perform(post("/api/v1/integration/video-webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidPayload)))
                .andExpect(status().isBadRequest());
    }
}
