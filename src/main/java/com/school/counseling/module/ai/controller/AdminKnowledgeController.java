package com.school.counseling.module.ai.controller;

import com.school.counseling.common.dto.ApiResponse;
import com.school.counseling.module.ai.dto.DocumentProcessingStatusDto;
import com.school.counseling.module.ai.entity.KnowledgeDocument;
import com.school.counseling.module.ai.repository.KnowledgeDocumentRepository;
import com.school.counseling.module.ai.service.RagKnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Year;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Controller dành riêng cho Quản trị viên (Admin / SuperAdmin) quản lý kho tri thức quy chế PDF.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/knowledge")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@RequiredArgsConstructor
public class AdminKnowledgeController {

    private final KnowledgeDocumentRepository documentRepository;
    private final RagKnowledgeService ragKnowledgeService;

    @Value("${app.storage.upload-dir:C:/upload}")
    private String uploadDir;

    /**
     * Tải lên tài liệu PDF Quy chế mới
     */
    @PostMapping(value = "/upload-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentProcessingStatusDto>> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "effectiveYear", required = false) Integer effectiveYear,
            @RequestParam(value = "supersededById", required = false) Long supersededById) {

        // 1. Kiểm tra file hợp lệ
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Tệp tải lên không được rỗng"));
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Chỉ chấp nhận tệp có định dạng .pdf"));
        }

        // 2. Lưu file vào thư mục lưu trữ
        try {
            File dir = new File(uploadDir, "knowledge_docs");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String storedFileName = UUID.randomUUID() + "_" + originalFilename;
            Path targetPath = dir.toPath().resolve(storedFileName);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            String finalTitle = (title != null && !title.isBlank()) ? title : originalFilename.replace(".pdf", "");
            int finalYear = (effectiveYear != null) ? effectiveYear : Year.now().getValue();

            // 3. Tạo bản ghi KnowledgeDocument ở trạng thái PENDING
            KnowledgeDocument document = KnowledgeDocument.builder()
                    .title(finalTitle)
                    .fileName(originalFilename)
                    .filePath(targetPath.toAbsolutePath().toString())
                    .effectiveYear(finalYear)
                    .status("PENDING")
                    .isActive(true)
                    .build();

            document = documentRepository.save(document);

            // 4. Kích hoạt tiến trình xử lý nền @Async
            ragKnowledgeService.processPdfDocumentAsync(document.getId(), supersededById);

            DocumentProcessingStatusDto responseDto = mapToStatusDto(document);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(responseDto, "Đã tiếp nhận tệp PDF và đang xử lý tạo vector nền."));

        } catch (IOException e) {
            log.error("[Admin RAG] Lỗi khi lưu tệp tải lên: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Không thể lưu trữ tệp: " + e.getMessage()));
        }
    }

    /**
     * Tra cứu tiến độ xử lý tài liệu PDF
     */
    @GetMapping("/documents/{id}/status")
    public ResponseEntity<ApiResponse<DocumentProcessingStatusDto>> getDocumentStatus(@PathVariable Long id) {
        return documentRepository.findById(id)
                .map(doc -> ResponseEntity.ok(ApiResponse.success(mapToStatusDto(doc))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("Không tìm thấy tài liệu #" + id)));
    }

    /**
     * Lấy danh sách các tài liệu quy chế đang hoạt động
     */
    @GetMapping("/documents")
    public ResponseEntity<ApiResponse<List<DocumentProcessingStatusDto>>> getActiveDocuments() {
        List<DocumentProcessingStatusDto> list = documentRepository.findByIsActiveTrueOrderByCreatedAtDesc().stream()
                .map(this::mapToStatusDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    private DocumentProcessingStatusDto mapToStatusDto(KnowledgeDocument doc) {
        return DocumentProcessingStatusDto.builder()
                .id(doc.getId())
                .title(doc.getTitle())
                .fileName(doc.getFileName())
                .effectiveYear(doc.getEffectiveYear())
                .status(doc.getStatus())
                .errorMessage(doc.getErrorMessage())
                .totalChunks(doc.getTotalChunks())
                .supersededById(doc.getSupersededBy() != null ? doc.getSupersededBy().getId() : null)
                .isActive(doc.getIsActive())
                .createdAt(doc.getCreatedAt())
                .build();
    }
}
