package com.school.counseling.module.ai.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KnowledgeChunkMatchDto {

    private Long id;
    private String title;
    private String content;
    private String sourceType; // 'REGULATION' hoặc 'FAQ_CHAT'
    private Integer effectiveYear;
    private Integer priorityLevel;
    private String departmentName;
    private Double similarityScore;
}
