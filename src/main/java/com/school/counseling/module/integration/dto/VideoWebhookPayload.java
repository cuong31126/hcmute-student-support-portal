/*
 * =========================================================================================
 * TÍNH NĂNG ĐÃ LOẠI BỎ THEO YÊU CẦU: MICROSERVICE NODE.JS VIDEO GENERATOR DTO
 * Toàn bộ mã nguồn dưới đây được chuyển về dạng chú thích (commented out) và vô hiệu hóa.
 * =========================================================================================
package com.school.counseling.module.integration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoWebhookPayload {

    @NotNull(message = "postId không được để trống")
    private Long postId;

    private String videoUrl;

    private Integer durationSeconds;

    @NotBlank(message = "status không được để trống")
    private String status; // COMPLETED, FAILED

    private String errorMessage;
}
*/

