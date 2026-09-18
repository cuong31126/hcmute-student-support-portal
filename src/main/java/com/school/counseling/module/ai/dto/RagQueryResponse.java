package com.school.counseling.module.ai.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagQueryResponse {

    private String answer;
    private String primarySourceType; // 'REGULATION' (Công văn chính thức) hoặc 'FAQ_CHAT' (Hỏi đáp lịch sử)
    private boolean needsHistoricalWarning;
    private double confidenceScore;
    private long executionTimeMs;
    private List<KnowledgeChunkMatchDto> matchedChunks;
}
