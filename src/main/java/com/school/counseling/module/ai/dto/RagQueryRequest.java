package com.school.counseling.module.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagQueryRequest {

    @NotBlank(message = "Câu hỏi không được để trống")
    private String question;

    private Long departmentId; // Tùy chọn lọc theo Khoa/Phòng
}
