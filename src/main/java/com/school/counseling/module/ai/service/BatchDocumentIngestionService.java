package com.school.counseling.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.counseling.module.ai.entity.KnowledgeChunk;
import com.school.counseling.module.ai.entity.KnowledgeDocument;
import com.school.counseling.module.ai.repository.KnowledgeChunkRepository;
import com.school.counseling.module.ai.repository.KnowledgeDocumentRepository;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.UserRepository;
import com.school.counseling.module.feed.entity.Post;
import com.school.counseling.module.feed.entity.PostAttachment;
import com.school.counseling.module.feed.repository.PostRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Service xử lý nạp tài liệu hàng loạt (Batch Ingestion) từ kho công văn PDF thực tế.
 * Tự động trích xuất metadata, băm chunk cấu trúc và sinh vector nhúng cho toàn hệ thống.
 */
@Slf4j
@Service
public class BatchDocumentIngestionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final GeminiApiClient geminiApiClient;
    private final RagKnowledgeService ragKnowledgeService;
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    public BatchDocumentIngestionService(
            KnowledgeDocumentRepository documentRepository,
            KnowledgeChunkRepository chunkRepository,
            GeminiApiClient geminiApiClient,
            RagKnowledgeService ragKnowledgeService,
            PostRepository postRepository,
            UserRepository userRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            PlatformTransactionManager transactionManager
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.geminiApiClient = geminiApiClient;
        this.ragKnowledgeService = ragKnowledgeService;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
    }

    @Getter
    @Builder
    public static class CampaignFeedSummary {
        private int totalPublished;
        private int totalSkipped;
        @Builder.Default
        private List<String> publishedTitles = new ArrayList<>();
    }

    @Getter
    @Builder
    public static class IngestionSummary {
        private int totalFilesScanned;
        private int successfulDocuments;
        private int skippedDocuments;
        private int failedDocuments;
        private int totalChunksCreated;
        private long totalExecutionTimeMs;
        @Builder.Default
        private List<String> errorMessages = new ArrayList<>();
    }

    /**
     * Chiến dịch đăng tải toàn bộ công văn quy chế chính thức lên Bảng tin (Feed Announcements)
     */
    @Transactional
    public CampaignFeedSummary publishAllDocumentsToFeedCampaign(User currentStaffOrAdmin) {
        List<KnowledgeDocument> activeDocs = documentRepository.findAll();
        int published = 0;
        int skipped = 0;
        List<String> publishedTitles = new ArrayList<>();

        User author = currentStaffOrAdmin;
        if (author == null && userRepository != null) {
            author = userRepository.findAll().stream()
                    .filter(u -> u.getRole() != null && ("ROLE_ADMIN".equalsIgnoreCase(u.getRole().getName()) || "ROLE_STAFF".equalsIgnoreCase(u.getRole().getName())))
                    .findFirst()
                    .orElse(null);
        }

        for (KnowledgeDocument doc : activeDocs) {
            if (!Boolean.TRUE.equals(doc.getIsActive())) {
                continue;
            }

            String postTitle = "[CÔNG VĂN QUY CHẾ] " + doc.getTitle();
            if (postTitle.length() > 255) {
                postTitle = postTitle.substring(0, 252) + "...";
            }

            // Kiểm tra chống trùng lặp: nếu đã đăng bài viết có tiêu đề này rồi thì bỏ qua
            if (postRepository.existsByTitle(postTitle)) {
                skipped++;
                continue;
            }

            // Xây dựng nội dung tóm tắt học vụ tinh gọn
            StringBuilder content = new StringBuilder();
            content.append("Văn bản quy chế / thông báo học vụ chính thức áp dụng tại Trường Đại học Sư phạm Kỹ thuật TP.HCM (HCMUTE).\n\n");
            content.append("• Tên văn bản: ").append(doc.getTitle()).append("\n");
            if (doc.getDocumentCode() != null && !doc.getDocumentCode().isBlank()) {
                content.append("• Số hiệu công văn: ").append(doc.getDocumentCode()).append("\n");
            }
            if (doc.getEffectiveYear() != null) {
                content.append("• Năm hiệu lực: ").append(doc.getEffectiveYear()).append("\n");
            }
            if (doc.getCategory() != null && !doc.getCategory().isBlank()) {
                content.append("• Phân loại: ").append(doc.getCategory()).append("\n");
            }
            if (doc.getPageCount() != null && doc.getPageCount() > 0) {
                content.append("• Quy mô: ").append(doc.getPageCount()).append(" trang văn bản\n");
            }
            content.append("\nSinh viên và Cán bộ có thể xem trực tiếp văn bản qua trình đọc PDF hoặc tải về tệp đính kèm bên dưới.");

            Post post = Post.builder()
                    .title(postTitle)
                    .content(content.toString())
                    .postType("OFFICIAL_ANNOUNCEMENT")
                    .status("APPROVED")
                    .author(author)
                    .isPinned(false)
                    .build();

            // Đính kèm file PDF gốc vào bài viết
            if (doc.getFilePath() != null && !doc.getFilePath().isBlank()) {
                PostAttachment attachment = PostAttachment.builder()
                        .post(post)
                        .fileName(doc.getFileName() != null ? doc.getFileName() : (doc.getTitle() + ".pdf"))
                        .fileUrl(doc.getFilePath())
                        .fileType("PDF")
                        .fileSize(doc.getFileSize() != null ? doc.getFileSize() : 0L)
                        .sourceType("DIRECT_UPLOAD")
                        .build();
                post.addAttachment(attachment);
            }

            postRepository.save(post);
            published++;
            publishedTitles.add(doc.getTitle());
        }

        log.info("[Feed Campaign] Đã đăng tải {} công văn lên Bảng tin (bỏ qua {} công văn trùng lặp)", published, skipped);
        return CampaignFeedSummary.builder()
                .totalPublished(published)
                .totalSkipped(skipped)
                .publishedTitles(publishedTitles)
                .build();
    }

    /**
     * Quét và nạp toàn bộ tài liệu từ các thư mục năm (2024, 2025, 2026)
     */
    public IngestionSummary ingestAllYearFolders(String rootPath) {
        long startTime = System.currentTimeMillis();
        File rootDir = new File(rootPath);
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            throw new IllegalArgumentException("Thư mục tài liệu không tồn tại: " + rootPath);
        }

        int totalScanned = 0;
        int successful = 0;
        int skipped = 0;
        int failed = 0;
        int totalChunks = 0;
        List<String> errors = new ArrayList<>();

        // Quét các thư mục con (ví dụ 2024, 2025, 2026)
        File[] subDirs = rootDir.listFiles(File::isDirectory);
        if (subDirs != null) {
            Arrays.sort(subDirs, Comparator.comparing(File::getName));
            for (File subDir : subDirs) {
                int year = parseYearFromFolderName(subDir.getName());
                File[] pdfFiles = subDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
                if (pdfFiles == null) continue;

                for (File pdf : pdfFiles) {
                    totalScanned++;
                    try {
                        int chunksCreated = ingestSingleDocument(pdf, year);
                        if (chunksCreated > 0) {
                            successful++;
                            totalChunks += chunksCreated;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        failed++;
                        String err = "Loi khi xu ly file " + pdf.getName() + ": " + e.getMessage();
                        log.warn("[Batch Ingestion] {}", err);
                        errors.add(err);
                    }
                }
            }
        }

        // Đồng bộ lại bộ nhớ đệm RAM Vector Cache
        if (successful > 0) {
            ragKnowledgeService.reloadVectorCache();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Batch Ingestion] Hoan thanh quet {} file. Thanh cong: {}, Bo qua: {}, That bai: {}, Tong chunks: {} ({} ms)",
                totalScanned, successful, skipped, failed, totalChunks, elapsed);

        return IngestionSummary.builder()
                .totalFilesScanned(totalScanned)
                .successfulDocuments(successful)
                .skippedDocuments(skipped)
                .failedDocuments(failed)
                .totalChunksCreated(totalChunks)
                .totalExecutionTimeMs(elapsed)
                .errorMessages(errors)
                .build();
    }

    /**
     * Nạp một file PDF đơn lẻ vào cơ sở dữ liệu với Transaction độc lập
     * @return Số lượng chunk được tạo (0 nếu tệp đã nạp và bị bỏ qua hoặc lỗi)
     */
    public int ingestSingleDocument(File pdfFile, int effectiveYear) {
        if (transactionTemplate != null) {
            Integer result = transactionTemplate.execute(status -> {
                try {
                    return doIngestSingleDocument(pdfFile, effectiveYear);
                } catch (Exception e) {
                    log.error("[Batch Ingestion] Lỗi khi xử lý file {}: {}", pdfFile.getName(), e.getMessage());
                    return 0;
                }
            });
            return result != null ? result : 0;
        } else {
            try {
                return doIngestSingleDocument(pdfFile, effectiveYear);
            } catch (Exception e) {
                log.error("[Batch Ingestion] Lỗi khi xử lý file {}: {}", pdfFile.getName(), e.getMessage());
                return 0;
            }
        }
    }

    private int doIngestSingleDocument(File pdfFile, int effectiveYear) throws Exception {
        String canonicalPath = pdfFile.getCanonicalPath();

        // 1. Kiểm tra chống trùng lặp nếu đã nạp thành công
        if (documentRepository.existsByFilePath(canonicalPath)) {
            var existingDoc = documentRepository.findByFilePath(canonicalPath);
            if (existingDoc.isPresent() && "COMPLETED".equals(existingDoc.get().getStatus()) && existingDoc.get().getTotalChunks() != null && existingDoc.get().getTotalChunks() > 0) {
                log.debug("[Batch Ingestion] Bỏ qua file đã hoàn thành: {}", pdfFile.getName());
                return 0;
            }
        }

        // 2. Chuẩn bị / cập nhật entity KnowledgeDocument
        KnowledgeDocument document = documentRepository.findByFilePath(canonicalPath)
                .orElse(KnowledgeDocument.builder()
                        .fileName(pdfFile.getName())
                        .filePath(canonicalPath)
                        .build());

        String cleanTitle = cleanDocumentTitle(pdfFile.getName());
        if (cleanTitle.length() > 240) {
            cleanTitle = cleanTitle.substring(0, 237) + "...";
        }
        document.setTitle(cleanTitle);
        document.setEffectiveYear(effectiveYear);
        document.setFileSize(pdfFile.length());
        document.setIsActive(true);

        // 3. Trích xuất văn bản từng trang
        List<PdfExtractorUtils.PageContent> pages;
        try {
            pages = PdfExtractorUtils.extractPagesWithInfo(pdfFile);
        } catch (Exception ex) {
            log.warn("[Batch Ingestion] Không thể bóc tách text từ file scan {}: {}", pdfFile.getName(), ex.getMessage());
            pages = Collections.emptyList();
        }

        if (pages.isEmpty()) {
            document.setStatus("FAILED");
            document.setPageCount(0);
            document.setTotalChunks(0);
            document.setErrorMessage("Tệp PDF dạng ảnh chụp/quét (scanned image), không có lớp văn bản số.");
            documentRepository.save(document);
            return 0;
        }

        String firstPageText = pages.get(0).text();
        String docCode = PdfExtractorUtils.extractDocumentCode(firstPageText);
        if (docCode != null && docCode.length() > 90) {
            docCode = docCode.substring(0, 87) + "...";
        }
        String category = PdfExtractorUtils.detectCategory(pdfFile.getName(), firstPageText);
        if (category != null && category.length() > 45) {
            category = category.substring(0, 42) + "...";
        }

        document.setDocumentCode(docCode != null ? docCode : "Đang cập nhật");
        document.setCategory(category);
        document.setPageCount(pages.size());
        document.setStatus("PROCESSING");
        document = documentRepository.save(document);

        // 4. Phân mảnh có cấu trúc ngữ nghĩa
        try {
            List<PdfExtractorUtils.StructuralChunk> structuralChunks = PdfExtractorUtils.chunkStructural(
                    pages,
                    cleanTitle,
                    docCode,
                    effectiveYear,
                    "HCMUTE"
            );

            if (structuralChunks.isEmpty()) {
                document.setStatus("FAILED");
                document.setTotalChunks(0);
                document.setErrorMessage("Không bóc tách được phân đoạn (chunk) hợp lệ từ nội dung văn bản.");
                documentRepository.save(document);
                return 0;
            }

            // Xóa các chunk cũ nếu nạp lại
            chunkRepository.deactivateByDocumentId(document.getId());

            List<KnowledgeChunk> chunksToSave = new ArrayList<>();
            int chunkSeq = 1;

            for (PdfExtractorUtils.StructuralChunk sc : structuralChunks) {
                // Phòng vệ Rate Limit Gemini API (tránh lỗi 429 khi nạp hàng loạt)
                try {
                    Thread.sleep(35);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                // BR-03: embedding tạo từ injectedContent (có header ngữ cảnh) để tối đa hóa recall
                float[] embedding = geminiApiClient.getEmbedding(sc.injectedContent());
                String embeddingJson = OBJECT_MAPPER.writeValueAsString(embedding);

                String chunkTitle = cleanTitle + " - Trang " + sc.pageNumber() + " (#" + (chunkSeq++) + ")";
                if (chunkTitle.length() > 240) {
                    chunkTitle = chunkTitle.substring(0, 237) + "...";
                }

                String safeArticleHeader = sc.articleHeader();
                if (safeArticleHeader != null && safeArticleHeader.length() > 240) {
                    safeArticleHeader = safeArticleHeader.substring(0, 237) + "...";
                }

                KnowledgeChunk chunk = KnowledgeChunk.builder()
                        .document(document)
                        .title(chunkTitle)
                        .content(sc.injectedContent())    // injectedContent — dùng cho debug/preview modal
                        .rawContent(sc.rawContent())      // BR-01: plain text sạch — dùng cho LLM context
                        .sourceType("REGULATION")
                        .effectiveYear(effectiveYear)
                        .priorityLevel(1)
                        .pageNumber(sc.pageNumber())
                        .articleHeader(safeArticleHeader)
                        .embeddingJson(embeddingJson)
                        .isActive(true)
                        .isDeprecated(false)
                        .build();

                chunk.setCachedEmbedding(embedding);
                chunksToSave.add(chunk);
            }

            chunkRepository.saveAll(chunksToSave);

            // 5. Cập nhật trạng thái hoàn thành
            document.setStatus("COMPLETED");
            document.setTotalChunks(chunksToSave.size());
            document.setErrorMessage(null);
            documentRepository.save(document);

            log.info("[Batch Ingestion] ✅ Đã nạp thành công '{}' ({}) - {} chunks",
                    cleanTitle, docCode != null ? docCode : "No-Code", chunksToSave.size());

            return chunksToSave.size();
        } catch (Exception ex) {
            log.error("[Batch Ingestion] ❌ Lỗi xử lý chunks cho tệp '{}': {}", pdfFile.getName(), ex.getMessage(), ex);
            document.setStatus("FAILED");
            String err = ex.getMessage();
            if (err != null && err.length() > 490) {
                err = err.substring(0, 487) + "...";
            }
            document.setErrorMessage(err != null ? err : "Lỗi khi lưu trữ chunks");
            document.setTotalChunks(0);
            documentRepository.save(document);
            return 0;
        }
    }

    private int parseYearFromFolderName(String folderName) {
        try {
            int y = Integer.parseInt(folderName.trim());
            if (y >= 2000 && y <= 2050) return y;
        } catch (NumberFormatException ignored) {}
        return 2026;
    }

    /**
     * Dọn sạch toàn bộ công văn và chunk PDF đã nạp để giải phóng dung lượng ổ đĩa
     */
    @Transactional
    public void deleteAllRegulationDocuments() {
        log.info("[Batch Ingestion] Đang xóa toàn bộ chunk công văn REGULATION khỏi Database...");
        chunkRepository.deleteAllRegulationChunks();
        documentRepository.deleteAll();
        ragKnowledgeService.reloadVectorCache();
        log.info("[Batch Ingestion] ✅ Đã giải phóng hoàn toàn kho công văn, đưa database về trạng thái siêu nhẹ!");
    }

    private String cleanDocumentTitle(String fileName) {
        String title = fileName.replaceAll("(?i)\\.pdf$", "");
        title = title.replaceAll("(?i)[-_\\s]+Signed.*$", "");
        title = title.replaceAll("(?i)[-_\\s]+Final.*$", "");
        title = title.replaceAll("(?i)[-_\\s]+\\(\\d+\\)$", "");
        return title.trim();
    }
}
