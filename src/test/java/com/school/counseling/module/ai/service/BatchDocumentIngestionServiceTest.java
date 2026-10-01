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
}
