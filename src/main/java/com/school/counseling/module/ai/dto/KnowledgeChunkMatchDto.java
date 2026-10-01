package com.school.counseling.module.ai.dto;

import lombok.*;

/**
 * DTO trả về thông tin đoạn tri thức trùng khớp kèm siêu dữ liệu trích dẫn công văn
 */
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

    // Các trường phục vụ Provenance & PDF Preview Modal
    private Integer pageNumber;
    private String articleHeader;
    private String documentCode;
    private String filePath;
}
