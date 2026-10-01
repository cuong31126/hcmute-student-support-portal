package com.school.counseling.module.ai.controller;

import com.school.counseling.common.dto.ApiResponse;
import com.school.counseling.module.ai.entity.KnowledgeChunk;
import com.school.counseling.module.ai.entity.KnowledgeDocument;
import com.school.counseling.module.ai.repository.KnowledgeChunkRepository;
import com.school.counseling.module.ai.repository.KnowledgeDocumentRepository;
import com.school.counseling.module.ai.service.BatchDocumentIngestionService;
import com.school.counseling.module.ai.service.RagKnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller phục vụ phân hệ Quản trị Tri thức AI (AI Knowledge Hub)
 * Quản lý kho công văn, kích hoạt Batch Ingestion, rà soát tri thức và trực quan hóa 3D Vector Space.
 */
@Slf4j
@Controller
@RequestMapping("/admin/knowledge")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminKnowledgeHubWebController {

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final BatchDocumentIngestionService batchIngestionService;
    private final RagKnowledgeService ragKnowledgeService;

    @GetMapping
    public String index() {
        return "redirect:/admin/knowledge/documents";
    }

    /**
     * Màn hình Quản lý Kho Công Văn & Quy Chế
     */
    @GetMapping("/documents")
    public String viewDocuments(Model model) {
        List<KnowledgeDocument> documents = documentRepository.findAll(
                Sort.by(Sort.Direction.DESC, "effectiveYear").and(Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        long activeChunksCount = chunkRepository.count();
        long completedDocsCount = documents.stream().filter(d -> "COMPLETED".equals(d.getStatus())).count();

        model.addAttribute("documents", documents);
        model.addAttribute("totalDocs", documents.size());
        model.addAttribute("completedDocs", completedDocsCount);
        model.addAttribute("totalChunks", activeChunksCount);

        return "admin/knowledge/documents";
    }

    /**
     * API kích hoạt quét và nạp hàng loạt 91 tệp PDF công văn
     */
    @PostMapping("/batch-ingest")
    @ResponseBody
    public ResponseEntity<ApiResponse<BatchDocumentIngestionService.IngestionSummary>> triggerBatchIngest(
            @RequestParam(defaultValue = "D:\\HK5\\CongNghePhanMem\\tailieuAI") String rootPath
    ) {
        try {
            log.info("[Admin Knowledge Hub] Kich hoat Batch Ingestion tu duong dan: {}", rootPath);
            var summary = batchIngestionService.ingestAllYearFolders(rootPath);
            return ResponseEntity.ok(ApiResponse.success(summary, "Quét và nạp dữ liệu hoàn tất thành công!"));
        } catch (Exception e) {
            log.error("[Admin Knowledge Hub] Loi Batch Ingestion: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(ApiResponse.error("Thất bại khi nạp kho công văn: " + e.getMessage()));
        }
    }

    /**
     * Màn hình 3D Vector Space & Similarity Visualizer
     */
    @GetMapping("/visualizer")
    public String view3DVisualizer(Model model) {
        long totalChunks = chunkRepository.count();
        model.addAttribute("totalChunks", totalChunks);
        return "admin/knowledge/visualizer";
    }

    /**
     * REST API cung cấp dữ liệu đồ thị 3D WebGL (Nodes & Links)
     */
    @GetMapping("/api/visualizer/graph")
    @ResponseBody
    public ResponseEntity<ApiResponse<Map<String, Object>>> get3DGraphData(
            @RequestParam(required = false) String query
    ) {
        Map<String, Object> graphData = ragKnowledgeService.get3DVectorGraphData(query);
        return ResponseEntity.ok(ApiResponse.success(graphData));
    }

    /**
     * Bật/Tắt hiệu lực của một đoạn Chunk
     */
    @PostMapping("/api/chunks/{id}/toggle-active")
    @ResponseBody
    public ResponseEntity<ApiResponse<Boolean>> toggleChunkActive(@PathVariable Long id) {
        return chunkRepository.findById(id).map(chunk -> {
            boolean newState = !Boolean.TRUE.equals(chunk.getIsActive());
            chunk.setIsActive(newState);
            chunkRepository.save(chunk);
            ragKnowledgeService.reloadVectorCache();
            return ResponseEntity.ok(ApiResponse.success(newState, "Đã cập nhật trạng thái chunk"));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
