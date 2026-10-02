package com.school.counseling.module.ai.service;

import com.school.counseling.module.ai.entity.KnowledgeChunk;
import com.school.counseling.module.ai.entity.KnowledgeDocument;
import com.school.counseling.module.ai.repository.KnowledgeChunkRepository;
import com.school.counseling.module.ai.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchDocumentIngestionServiceTest {

    @Mock
    private KnowledgeDocumentRepository documentRepository;

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private GeminiApiClient geminiApiClient;

    @Mock
    private RagKnowledgeService ragKnowledgeService;

    @Mock
    private com.school.counseling.module.feed.repository.PostRepository postRepository;

    @Mock
    private com.school.counseling.module.auth.repository.UserRepository userRepository;

    @InjectMocks
    private BatchDocumentIngestionService ingestionService;

    @Test
    @DisplayName("TDD-INGEST-01: Bỏ qua nạp lại nếu file đã tồn tại và trạng thái COMPLETED")
    void ingestSingleDocument_alreadyExistsAndCompleted_skipsIngestion() throws Exception {
        File mockFile = mock(File.class);
        when(mockFile.getCanonicalPath()).thenReturn("D:\\mock\\sample.pdf");
        when(mockFile.getName()).thenReturn("sample.pdf");

        KnowledgeDocument existingDoc = KnowledgeDocument.builder()
                .status("COMPLETED")
                .filePath("D:\\mock\\sample.pdf")
                .build();

        when(documentRepository.existsByFilePath("D:\\mock\\sample.pdf")).thenReturn(true);
        when(documentRepository.findByFilePath("D:\\mock\\sample.pdf")).thenReturn(Optional.of(existingDoc));

        int result = ingestionService.ingestSingleDocument(mockFile, 2026);

        assertEquals(0, result);
        verify(chunkRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("TDD-INGEST-02: Báo lỗi nếu đường dẫn root không tồn tại")
    void ingestAllYearFolders_invalidPath_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                ingestionService.ingestAllYearFolders("D:\\Path\\Does\\Not\\Exist_12345")
        );
    }

    @Test
    @DisplayName("TDD-CAMPAIGN-01: Đăng tải công văn lên bảng tin thành công, bỏ qua bài trùng")
    void publishAllDocumentsToFeedCampaign_publishesNewAndSkipsExisting() {
        KnowledgeDocument doc1 = KnowledgeDocument.builder()
                .title("Quy chế đào tạo 2026")
                .documentCode("1084/QĐ-ĐHSPKT")
                .filePath("D:\\mock\\doc1.pdf")
                .fileName("doc1.pdf")
                .effectiveYear(2026)
                .isActive(true)
                .build();

        KnowledgeDocument doc2 = KnowledgeDocument.builder()
                .title("Quy định học bổng 2025")
                .documentCode("500/QĐ-ĐHSPKT")
                .filePath("D:\\mock\\doc2.pdf")
                .fileName("doc2.pdf")
                .effectiveYear(2025)
                .isActive(true)
                .build();

        when(documentRepository.findAll()).thenReturn(List.of(doc1, doc2));
        when(postRepository.existsByTitle("[CÔNG VĂN QUY CHẾ] Quy chế đào tạo 2026")).thenReturn(false);
        when(postRepository.existsByTitle("[CÔNG VĂN QUY CHẾ] Quy định học bổng 2025")).thenReturn(true);

        var summary = ingestionService.publishAllDocumentsToFeedCampaign(null);

        assertEquals(1, summary.getTotalPublished());
        assertEquals(1, summary.getTotalSkipped());
        assertEquals(1, summary.getPublishedTitles().size());
        verify(postRepository, times(1)).save(any());
    }
}
