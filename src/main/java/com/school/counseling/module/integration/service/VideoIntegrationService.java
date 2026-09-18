package com.school.counseling.module.integration.service;

import com.school.counseling.module.feed.entity.Post;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoIntegrationService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.integration.node-video-url:http://localhost:5000/api/video/generate}")
    private String nodeVideoApiUrl;

    @Value("${app.integration.callback-base-url:http://localhost:8080}")
    private String callbackBaseUrl;

    /**
     * Gửi yêu cầu sinh video tóm tắt 40s bất đồng bộ sang Microservice Node.js
     */
    @Async
    public void requestVideoGeneration(Post post) {
        if (post == null || post.getId() == null) {
            log.warn("Bỏ qua tạo video vì Post rỗng");
            return;
        }

        String callbackUrl = callbackBaseUrl + "/api/v1/integration/video-webhook";

        Map<String, Object> payload = new HashMap<>();
        payload.put("postId", post.getId());
        payload.put("title", post.getTitle());
        payload.put("summary", post.getContent());
        payload.put("callbackUrl", callbackUrl);

        try {
            log.info("Đang gửi yêu cầu tạo video tới Node.js cho Post #{} (Title: {})", post.getId(), post.getTitle());
            restTemplate.postForEntity(nodeVideoApiUrl, payload, Map.class);
            log.info("Node.js đã chấp nhận tiến trình render video cho Post #{}", post.getId());
        } catch (Exception e) {
            log.error("Không thể kết nối tới Microservice Node.js Video Generator: {}", e.getMessage());
        }
    }
}
