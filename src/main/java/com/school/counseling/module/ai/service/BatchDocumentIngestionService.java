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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Service xử lý nạp tài liệu hàng loạt (Batch Ingestion) từ kho công văn PDF thực tế.
 * Tự động trích xuất metadata, băm chunk cấu trúc và sinh vector nhúng cho toàn hệ thống.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchDocumentIngestionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final GeminiApiClient geminiApiClient;
    private final RagKnowledgeService ragKnowledgeService;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

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

            // Xây dựng nội dung bài đăng học đường trang trọng
            StringBuilder content = new StringBuilder();
            content.append("🏛️ **THÔNG BÁO VỀ CÔNG VĂN / QUY CHẾ HỌC VỤ CHÍNH THỨC**\n\n");
            content.append("- **Tên văn bản:** ").append(doc.getTitle()).append("\n");
            if (doc.getDocumentCode() != null && !doc.getDocumentCode().isBlank()) {
                content.append("- **Số hiệu công văn:** ").append(doc.getDocumentCode()).append("\n");
            }
            if (doc.getEffectiveYear() != null) {
                content.append("- **Năm hiệu lực:** ").append(doc.getEffectiveYear()).append("\n");
            }
            if (doc.getCategory() != null && !doc.getCategory().isBlank()) {
                content.append("- **Phân loại:** ").append(doc.getCategory()).append("\n");
            }
            if (doc.getPageCount() != null && doc.getPageCount() > 0) {
                content.append("- **Quy mô:** ").append(doc.getPageCount()).append(" trang văn bản\n");
            }
            content.append("\nQuy định trên được ban hành và áp dụng chính thức tại Trường Đại học Sư phạm Kỹ thuật TP.HCM (HCMUTE). ");
            content.append("Sinh viên vui lòng tải về hoặc mở file đính kèm dưới đây để nắm rõ quyền lợi và nghĩa vụ học vụ.\n\n");
            content.append("*Mọi thắc mắc liên quan có thể hỏi trực tiếp AI Trợ lý ảo QAUTE hoặc gửi Phiếu hỗ trợ (Ticket).*");

            boolean isPinned = doc.getEffectiveYear() != null && doc.getEffectiveYear() >= 2026;

            Post post = Post.builder()
                    .title(postTitle)
                    .content(content.toString())
                    .postType("OFFICIAL_ANNOUNCEMENT")
                    .status("APPROVED")
                    .author(author)
                    .isPinned(isPinned)
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
     * Nạp một file PDF đơn lẻ vào cơ sở dữ liệu
     * @return Số lượng chunk được tạo (0 nếu tệp đã nạp và bị bỏ qua)
     */
    @Transactional
    public int ingestSingleDocument(File pdfFile, int effectiveYear) throws Exception {
        String canonicalPath = pdfFile.getCanonicalPath();

        // 1. Kiểm tra chống trùng lặp nếu đã nạp thành công
        if (documentRepository.existsByFilePath(canonicalPath)) {
            var existingDoc = documentRepository.findByFilePath(canonicalPath);
            if (existingDoc.isPresent() && "COMPLETED".equals(existingDoc.get().getStatus())) {
                log.debug("[Batch Ingestion] Bo qua file da ton tai: {}", pdfFile.getName());
                return 0;
            }
        }

        // 2. Trích xuất văn bản từng trang
        List<PdfExtractorUtils.PageContent> pages = PdfExtractorUtils.extractPagesWithInfo(pdfFile);
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("Không thể trích xuất văn bản số từ file PDF");
        }

        String firstPageText = pages.get(0).text();
        String docCode = PdfExtractorUtils.extractDocumentCode(firstPageText);
        String category = PdfExtractorUtils.detectCategory(pdfFile.getName(), firstPageText);
        String cleanTitle = cleanDocumentTitle(pdfFile.getName());

        // 3. Tạo hoặc cập nhật Document Entity
        KnowledgeDocument document = documentRepository.findByFilePath(canonicalPath)
                .orElse(KnowledgeDocument.builder()
                        .fileName(pdfFile.getName())
                        .filePath(canonicalPath)
                        .build());

        document.setTitle(cleanTitle);
        document.setDocumentCode(docCode != null ? docCode : "Đang cập nhật");
        document.setEffectiveYear(effectiveYear);
        document.setCategory(category);
        document.setFileSize(pdfFile.length());
        document.setPageCount(pages.size());
        document.setStatus("PROCESSING");
        document.setIsActive(true);

        document = documentRepository.save(document);

        // 4. Phân mảnh có cấu trúc ngữ nghĩa
        List<PdfExtractorUtils.StructuralChunk> structuralChunks = PdfExtractorUtils.chunkStructural(
                pages,
                cleanTitle,
                docCode,
                effectiveYear,
                "HCMUTE"
        );

        // Xóa các chunk cũ nếu nạp lại
        chunkRepository.deactivateByDocumentId(document.getId());

        List<KnowledgeChunk> chunksToSave = new ArrayList<>();
        int chunkSeq = 1;

        for (PdfExtractorUtils.StructuralChunk sc : structuralChunks) {
            // BR-03: embedding tạo từ injectedContent (có header ngữ cảnh) để tối đa hóa recall
            float[] embedding = geminiApiClient.getEmbedding(sc.injectedContent());
            String embeddingJson = OBJECT_MAPPER.writeValueAsString(embedding);

            KnowledgeChunk chunk = KnowledgeChunk.builder()
                    .document(document)
                    .title(cleanTitle + " - Trang " + sc.pageNumber() + " (#" + chunkSeq++ + ")")
                    .content(sc.injectedContent())    // injectedContent — dùng cho debug/preview modal
                    .rawContent(sc.rawContent())      // BR-01: plain text sạch — dùng cho LLM context
                    .sourceType("REGULATION")
                    .effectiveYear(effectiveYear)
                    .priorityLevel(1)
                    .pageNumber(sc.pageNumber())
                    .articleHeader(sc.articleHeader())
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

        log.info("[Batch Ingestion] Đã nạp thành công '{}' ({}) - {} chunks",
                cleanTitle, docCode != null ? docCode : "No-Code", chunksToSave.size());

        return chunksToSave.size();
    }

    private int parseYearFromFolderName(String folderName) {
        try {
            int y = Integer.parseInt(folderName.trim());
            if (y >= 2000 && y <= 2050) return y;
        } catch (NumberFormatException ignored) {}
        return 2026;
    }

    private String cleanDocumentTitle(String fileName) {
        String title = fileName.replaceAll("(?i)\\.pdf$", "");
        title = title.replaceAll("(?i)[-_\\s]+Signed.*$", "");
        title = title.replaceAll("(?i)[-_\\s]+Final.*$", "");
        title = title.replaceAll("(?i)[-_\\s]+\\(\\d+\\)$", "");
        return title.trim();
    }
}
