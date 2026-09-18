package com.school.counseling.module.ai.service;

import com.school.counseling.module.ai.entity.KnowledgeChunk;
import com.school.counseling.module.ai.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagKnowledgeServiceTest {

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private GeminiApiClient geminiApiClient;

    private RagKnowledgeService ragKnowledgeService;

    @BeforeEach
    void setUp() {
        ragKnowledgeService = new RagKnowledgeService(chunkRepository, geminiApiClient);
    }

    @Test
    @DisplayName("TDD-RAG-04: Tìm thấy chunk Tầng 1 (REGULATION) với điểm >= 0.75 -> Dừng quét, lấy ngay Tầng 1")
    void whenTier1HasHighConfidence_shouldReturnTier1Immediately() {
        float[] queryVector = new float[]{0.5f, 0.5f};
        when(geminiApiClient.getEmbedding(anyString())).thenReturn(queryVector);

        KnowledgeChunk officialChunk = KnowledgeChunk.builder()
                .id(1L)
                .title("Quy chế học bổng 2026")
                .content("Sinh viên đạt điểm rèn luyện xuất sắc...")
                .sourceType("REGULATION")
                .priorityLevel(1)
                .effectiveYear(2026)
                .isActive(true)
                .build();
        officialChunk.setEmbeddingArray(new float[]{0.5f, 0.5f}); // Cosine = 1.0

        ragKnowledgeService.loadVectorsIntoMemory(List.of(officialChunk));

        var result = ragKnowledgeService.hierarchicalSearch("Học bổng 2026 cần điều kiện gì?");

        assertNotNull(result);
        assertEquals("REGULATION", result.getPrimarySourceType());
        assertFalse(result.isNeedsHistoricalWarning(), "Tầng 1 chính thức không được có nhãn cảnh báo lỗi thời");
        assertEquals(1, result.getMatchedChunks().size());
    }

    @Test
    @DisplayName("TDD-RAG-05: Tầng 1 không khớp (<0.75), rơi xuống Tầng 2 (FAQ_CHAT) -> Bắt buộc bật cờ cảnh báo lỗi thời")
    void whenTier1LowConfidence_shouldFallbackToTier2WithWarning() {
        float[] queryVector = new float[]{0.0f, 1.0f};
        when(geminiApiClient.getEmbedding(anyString())).thenReturn(queryVector);

        KnowledgeChunk officialChunk = KnowledgeChunk.builder()
                .id(1L)
                .sourceType("REGULATION")
                .priorityLevel(1)
                .effectiveYear(2026)
                .isActive(true)
                .build();
        officialChunk.setEmbeddingArray(new float[]{1.0f, 0.0f}); // Orthogonal = 0.0

        KnowledgeChunk chatChunk = KnowledgeChunk.builder()
                .id(2L)
                .title("Tư vấn dời phòng học năm 2021")
                .content("Phòng học A1 dời sang nhà xưởng")
                .sourceType("FAQ_CHAT")
                .priorityLevel(2)
                .effectiveYear(2021)
                .isActive(true)
                .build();
        chatChunk.setEmbeddingArray(new float[]{0.0f, 1.0f}); // Match = 1.0 (trừ time decay ~0.75)

        ragKnowledgeService.loadVectorsIntoMemory(List.of(officialChunk, chatChunk));

        var result = ragKnowledgeService.hierarchicalSearch("Phòng học dời đi đâu?");

        assertNotNull(result);
        assertEquals("FAQ_CHAT", result.getPrimarySourceType());
        assertTrue(result.isNeedsHistoricalWarning(), "Tầng 2 lịch sử bắt buộc phải bật cờ cảnh báo đối chiếu");
    }
}
