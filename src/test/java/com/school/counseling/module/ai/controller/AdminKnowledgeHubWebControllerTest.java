package com.school.counseling.module.ai.controller;

import com.school.counseling.module.ai.repository.KnowledgeChunkRepository;
import com.school.counseling.module.ai.repository.KnowledgeDocumentRepository;
import com.school.counseling.module.ai.service.BatchDocumentIngestionService;
import com.school.counseling.module.ai.service.RagKnowledgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminKnowledgeHubWebControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KnowledgeDocumentRepository documentRepository;

    @MockBean
    private KnowledgeChunkRepository chunkRepository;

    @MockBean
    private BatchDocumentIngestionService batchIngestionService;

    @MockBean
    private RagKnowledgeService ragKnowledgeService;

    @Test
    @WithMockUser(username = "admin@hcmute.edu.vn", roles = {"ADMIN"})
    @DisplayName("TDD-HUB-01: Truy cập /admin/knowledge/documents trả về 200 OK và view documents")
    void viewDocuments_asAdmin_returnsOk() throws Exception {
        when(documentRepository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of());
        when(chunkRepository.count()).thenReturn(100L);

        mockMvc.perform(get("/admin/knowledge/documents"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/knowledge/documents"))
                .andExpect(model().attributeExists("documents", "totalDocs", "totalChunks"));
    }

    @Test
    @WithMockUser(username = "admin@hcmute.edu.vn", roles = {"ADMIN"})
    @DisplayName("TDD-HUB-02: Truy cập /admin/knowledge/visualizer trả về 200 OK và view visualizer")
    void viewVisualizer_asAdmin_returnsOk() throws Exception {
        when(chunkRepository.count()).thenReturn(120L);

        mockMvc.perform(get("/admin/knowledge/visualizer"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/knowledge/visualizer"))
                .andExpect(model().attributeExists("totalChunks"));
    }

    @Test
    @WithMockUser(username = "admin@hcmute.edu.vn", roles = {"ADMIN"})
    @DisplayName("TDD-HUB-03: Gọi API /admin/knowledge/api/visualizer/graph trả về JSON nodes và links")
    void get3DGraphData_asAdmin_returnsJson() throws Exception {
        Map<String, Object> mockGraph = Map.of(
                "nodes", List.of(Map.of("id", "chunk-1", "name", "QĐ 1084", "color", "#4ade80")),
                "links", List.of()
        );
        when(ragKnowledgeService.get3DVectorGraphData(any())).thenReturn(mockGraph);

        mockMvc.perform(get("/admin/knowledge/api/visualizer/graph").param("query", "học phí"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nodes[0].name").value("QĐ 1084"));
    }
}
