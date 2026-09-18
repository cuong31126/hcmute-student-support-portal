package com.school.counseling.module.ai.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentProcessingStatusDto {

    private Long id;
    private String title;
    private String fileName;
    private Integer effectiveYear;
    private String status; // PENDING, PROCESSING, COMPLETED, FAILED
    private String errorMessage;
    private Integer totalChunks;
    private Long supersededById;
    private Boolean isActive;
    private LocalDateTime createdAt;
}
