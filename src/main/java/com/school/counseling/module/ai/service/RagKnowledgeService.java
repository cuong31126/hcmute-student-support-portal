package com.school.counseling.module.ai.service;

import com.school.counseling.module.ai.dto.KnowledgeChunkMatchDto;
import com.school.counseling.module.ai.dto.RagQueryResponse;
import com.school.counseling.module.ai.entity.KnowledgeChunk;
import com.school.counseling.module.ai.entity.KnowledgeDocument;
import com.school.counseling.module.ai.repository.KnowledgeChunkRepository;
import com.school.counseling.module.ai.repository.KnowledgeDocumentRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Year;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Service quản lý kho tri thức RAG và thuật toán tìm kiếm phân tầng (Hierarchical Retrieval).
 * Toàn bộ vector được lưu trong MySQL và nạp lên RAM để tính toán Cosine Similarity siêu tốc.
 */
@Slf4j
@Service
public class RagKnowledgeService {

    private static final double TIER_1_CONFIDENCE_THRESHOLD = 0.75;
    private static final double DEPARTMENT_BOOST_FACTOR = 1.20;

    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final GeminiApiClient geminiApiClient;

    private final List<KnowledgeChunk> inMemoryChunks = new CopyOnWriteArrayList<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RagKnowledgeService(KnowledgeChunkRepository chunkRepository,
                               KnowledgeDocumentRepository documentRepository,
                               GeminiApiClient geminiApiClient) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.geminiApiClient = geminiApiClient;
    }

    /**
     * Constructor phục vụ Unit Test Mockito
     */
    public RagKnowledgeService(KnowledgeChunkRepository chunkRepository, GeminiApiClient geminiApiClient) {
        this(chunkRepository, null, geminiApiClient);
    }

    @PostConstruct
    public void initCache() {
        if (chunkRepository != null) {
            reloadVectorCache();
        }
    }

    /**
     * Nạp toàn bộ vector các chunk đang active từ MySQL vào RAM
     */
    @Transactional(readOnly = true)
    public synchronized void reloadVectorCache() {
        try {
            List<KnowledgeChunk> activeChunks = chunkRepository.findAllActiveWithRelations();
            inMemoryChunks.clear();
            inMemoryChunks.addAll(activeChunks);
            log.info("[RAG] Đã nạp thành công {} vector tri thức vào bộ nhớ RAM Cache!", inMemoryChunks.size());
        } catch (Exception e) {
            log.warn("[RAG] Không thể nạp vector cache lúc khởi động: {}", e.getMessage());
        }
    }

    /**
     * Nạp thủ công danh sách chunk vào RAM (Phục vụ Unit Test)
     */
    public void loadVectorsIntoMemory(List<KnowledgeChunk> chunks) {
        inMemoryChunks.clear();
        if (chunks != null) {
            inMemoryChunks.addAll(chunks);
        }
    }

    /**
     * Thuật toán Tìm kiếm Phân tầng (Hierarchical Retrieval)
     *
     * @param rawQuery Câu hỏi của sinh viên
     * @param departmentId ID Khoa/Phòng cần ưu tiên (nếu có)
     * @return Kết quả tìm kiếm có phân cấp kèm cờ cảnh báo đối chiếu
     */
    public RagQueryResponse hierarchicalSearch(String rawQuery, Long departmentId) {
        long startTime = System.currentTimeMillis();

        // 1. Chuẩn hóa từ viết tắt học vụ (avđr -> anh văn đầu ra...)
        String expandedQuery = AcademicAbbreviationUtils.expand(rawQuery);
        float[] queryVector = geminiApiClient.getEmbedding(expandedQuery);

        int currentYear = Year.now().getValue();

        // 2. TẦNG 1: Quét trong kho Công văn / Quy chế chính thức (REGULATION)
        List<ScoredChunk> tier1Matches = new ArrayList<>();
        for (KnowledgeChunk chunk : inMemoryChunks) {
            if (!chunk.getIsActive() || !"REGULATION".equalsIgnoreCase(chunk.getSourceType())) {
                continue;
            }

            float[] chunkVec = chunk.getEmbeddingArray();
            if (chunkVec.length == 0 || chunkVec.length != queryVector.length) {
                continue;
            }

            double score = VectorMathUtils.cosineSimilarity(queryVector, chunkVec);

            // Ưu tiên theo Khoa/Phòng (Department Scope Boost)
            if (departmentId != null && chunk.getDepartment() != null
                    && Objects.equals(chunk.getDepartment().getId(), departmentId)) {
                score *= DEPARTMENT_BOOST_FACTOR;
            }

            tier1Matches.add(new ScoredChunk(chunk, score));
        }

        tier1Matches.sort((a, b) -> Double.compare(b.score, a.score));

        // Nếu Tầng 1 có kết quả tốt (>= 0.75) -> DỪNG QUÉT, lấy ngay Tầng 1
        if (!tier1Matches.isEmpty() && tier1Matches.get(0).score >= TIER_1_CONFIDENCE_THRESHOLD) {
            List<KnowledgeChunkMatchDto> matches = tier1Matches.stream()
                    .limit(3)
                    .map(this::mapToMatchDto)
                    .collect(Collectors.toList());

            long elapsed = System.currentTimeMillis() - startTime;
            return RagQueryResponse.builder()
                    .primarySourceType("REGULATION")
                    .needsHistoricalWarning(false)
                    .confidenceScore(tier1Matches.get(0).score)
                    .executionTimeMs(elapsed)
                    .matchedChunks(matches)
                    .build();
        }

        // 3. TẦNG 2: Mở rộng quét sang kho Lịch sử tư vấn (FAQ_CHAT)
        List<ScoredChunk> tier2Matches = new ArrayList<>();
        for (KnowledgeChunk chunk : inMemoryChunks) {
            if (!chunk.getIsActive() || !"FAQ_CHAT".equalsIgnoreCase(chunk.getSourceType())) {
                continue;
            }

            float[] chunkVec = chunk.getEmbeddingArray();
            if (chunkVec.length == 0 || chunkVec.length != queryVector.length) {
                continue;
            }

            double score = VectorMathUtils.cosineSimilarity(queryVector, chunkVec);

            // Time Decay: Giảm 5% điểm tương đồng cho mỗi năm cũ hơn
            int ageInYears = Math.max(0, currentYear - chunk.getEffectiveYear());
            double timeWeight = Math.max(0.60, 1.0 - (ageInYears * 0.05));
            score *= timeWeight;

            // Department Boost
            if (departmentId != null && chunk.getDepartment() != null
                    && Objects.equals(chunk.getDepartment().getId(), departmentId)) {
                score *= DEPARTMENT_BOOST_FACTOR;
            }

            tier2Matches.add(new ScoredChunk(chunk, score));
        }

        tier2Matches.sort((a, b) -> Double.compare(b.score, a.score));

        List<KnowledgeChunkMatchDto> matches = tier2Matches.stream()
                .limit(3)
                .map(this::mapToMatchDto)
                .collect(Collectors.toList());

        double bestScore = tier2Matches.isEmpty() ? 0.0 : tier2Matches.get(0).score;
        long elapsed = System.currentTimeMillis() - startTime;

        return RagQueryResponse.builder()
                .primarySourceType("FAQ_CHAT")
                .needsHistoricalWarning(true) // BẮT BUỘC gắn nhãn cảnh báo đối chiếu thời gian
                .confidenceScore(bestScore)
                .executionTimeMs(elapsed)
                .matchedChunks(matches)
                .build();
    }

    public RagQueryResponse hierarchicalSearch(String rawQuery) {
        return hierarchicalSearch(rawQuery, null);
    }

    /**
     * Xử lý nền (@Async) trích xuất văn bản từ PDF, băm chunk và tạo vector
     */
    @Async("mailTaskExecutor")
    @Transactional
    public void processPdfDocumentAsync(Long documentId, Long supersededById) {
        if (documentRepository == null) return;

        Optional<KnowledgeDocument> docOpt = documentRepository.findById(documentId);
        if (docOpt.isEmpty()) return;

        KnowledgeDocument document = docOpt.get();
        document.setStatus("PROCESSING");
        documentRepository.save(document);

        try {
            // 1. Xử lý cơ chế đào thải văn bản cũ nếu có
            if (supersededById != null) {
                Optional<KnowledgeDocument> oldDocOpt = documentRepository.findById(supersededById);
                if (oldDocOpt.isPresent()) {
                    KnowledgeDocument oldDoc = oldDocOpt.get();
                    oldDoc.setIsActive(false);
                    oldDoc.setSupersededBy(document);
                    documentRepository.save(oldDoc);
                    chunkRepository.deactivateByDocumentId(supersededById);
                    log.info("[RAG] Đã vô hiệu hóa văn bản cũ #{} do được thay thế bởi #{}", supersededById, documentId);
                }
            }

            // 2. Trích xuất text và kiểm tra layer chữ
            File pdfFile = new File(document.getFilePath());
            String fullText = PdfExtractorUtils.extractText(pdfFile);

            // 3. Phân mảnh văn bản thông minh (Chunking with overlap)
            List<String> textChunks = PdfExtractorUtils.chunkText(fullText);

            // 4. Tạo vector embeddings và lưu vào MySQL
            List<KnowledgeChunk> chunksToSave = new ArrayList<>();
            int chunkIndex = 1;

            for (String chunkContent : textChunks) {
                float[] embedding = geminiApiClient.getEmbedding(chunkContent);

                KnowledgeChunk chunk = KnowledgeChunk.builder()
                        .document(document)
                        .title(document.getTitle() + " (Đoạn " + chunkIndex++ + ")")
                        .content(chunkContent)
                        .sourceType("REGULATION")
                        .effectiveYear(document.getEffectiveYear())
                        .priorityLevel(1)
                        .isActive(true)
                        .build();

                chunk.setEmbeddingArray(embedding);
                chunksToSave.add(chunk);
            }

            chunkRepository.saveAll(chunksToSave);

            document.setStatus("COMPLETED");
            document.setTotalChunks(chunksToSave.size());
            documentRepository.save(document);

            // Nạp lại RAM Cache
            reloadVectorCache();

            log.info("[RAG] Xử lý thành công tệp PDF '{}', đã tạo {} chunks vector!", document.getTitle(), chunksToSave.size());
        } catch (Exception e) {
            log.error("[RAG] Thất bại khi xử lý tài liệu PDF #{}: {}", documentId, e.getMessage(), e);
            document.setStatus("FAILED");
            document.setErrorMessage(e.getMessage());
            documentRepository.save(document);
        }
    }

    private KnowledgeChunkMatchDto mapToMatchDto(ScoredChunk sc) {
        String deptName = sc.chunk.getDepartment() != null ? sc.chunk.getDepartment().getName() : "Toàn trường";
        return KnowledgeChunkMatchDto.builder()
                .id(sc.chunk.getId())
                .title(sc.chunk.getTitle())
                .content(sc.chunk.getContent())
                .sourceType(sc.chunk.getSourceType())
                .effectiveYear(sc.chunk.getEffectiveYear())
                .priorityLevel(sc.chunk.getPriorityLevel())
                .departmentName(deptName)
                .similarityScore(Math.round(sc.score * 10000.0) / 10000.0)
                .build();
    }

    private record ScoredChunk(KnowledgeChunk chunk, double score) {}
}
