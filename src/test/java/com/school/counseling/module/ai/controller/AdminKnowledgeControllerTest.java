package com.school.counseling.module.ai.controller;

import com.school.counseling.module.ai.repository.KnowledgeDocumentRepository;
import com.school.counseling.module.ai.service.RagKnowledgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminKnowledgeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagKnowledgeService ragKnowledgeService;

    @MockBean
    private KnowledgeDocumentRepository documentRepository;

    @Test
    @WithMockUser(roles = "STUDENT")
    @DisplayName("TDD-RAG-06: Sinh viên cố tình upload PDF Quy chế -> Bị chặn 403 Forbidden")
    void studentCannotUploadRegulationPdf() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "quy_che.pdf", "application/pdf", "%PDF-1.4 test".getBytes()
        );

        mockMvc.perform(multipart("/api/admin/knowledge/upload-pdf").file(file))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("TDD-RAG-07: Admin upload file không phải PDF (.exe / .txt) -> Bị từ chối 400 Bad Request")
    void adminUploadsInvalidFileType_shouldReturnBadRequest() throws Exception {
        MockMultipartFile exeFile = new MockMultipartFile(
                "file", "script.sh", "text/plain", "echo hack".getBytes()
        );

        mockMvc.perform(multipart("/api/admin/knowledge/upload-pdf").file(exeFile))
                .andExpect(status().isBadRequest());
    }
}
