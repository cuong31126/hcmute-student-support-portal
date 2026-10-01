package com.school.counseling.module.ai.service;

import com.school.counseling.module.ai.dto.KnowledgeChunkMatchDto;
import com.school.counseling.module.ai.dto.RagQueryResponse;
import com.school.counseling.module.ai.entity.KnowledgeChunk;
import com.school.counseling.module.ai.entity.KnowledgeDocument;
import com.school.counseling.module.ai.repository.KnowledgeChunkRepository;
import com.school.counseling.module.ai.repository.KnowledgeDocumentRepository;
import jakarta.annotation.PostConstruct;
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
 * Service quản lý kho tri thức RAG và thuật toán tìm kiếm kết hợp phân tầng (Hybrid Hierarchical Retrieval).
 * Lưu trữ vector trong MySQL, tải lên RAM Cache tính toán Cosine Similarity siêu tốc < 3ms
 * và hỗ trợ xuất dữ liệu mô phỏng không gian 3D WebGL (3D Vector Space Visualizer).
 */
@Slf4j
@Service
public class RagKnowledgeService {

    private static final double TIER_1_CONFIDENCE_THRESHOLD = 0.75;
    private static final double DEPARTMENT_BOOST_FACTOR = 1.25;

    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final GeminiApiClient geminiApiClient;
    private final com.school.counseling.module.ticket.repository.TicketRepository ticketRepository;

    private final List<KnowledgeChunk> inMemoryChunks = new CopyOnWriteArrayList<>();
    private volatile VectorReductionUtils.PcaModel pcaModel;

    @org.springframework.beans.factory.annotation.Autowired
    public RagKnowledgeService(KnowledgeChunkRepository chunkRepository,
                               KnowledgeDocumentRepository documentRepository,
                               GeminiApiClient geminiApiClient,
                               @org.springframework.beans.factory.annotation.Autowired(required = false)
                               com.school.counseling.module.ticket.repository.TicketRepository ticketRepository) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.geminiApiClient = geminiApiClient;
        this.ticketRepository = ticketRepository;
    }

    /**
     * Constructor phục vụ Unit Test Mockito
     */
    public RagKnowledgeService(KnowledgeChunkRepository chunkRepository, GeminiApiClient geminiApiClient) {
        this(chunkRepository, null, geminiApiClient, null);
    }

    @PostConstruct
    public void initCache() {
        if (chunkRepository != null) {
            reloadVectorCache();
        }
    }

    /**
     * Nạp toàn bộ vector các chunk đang active từ MySQL vào RAM và huấn luyện mô hình PCA 3D
     */
    @Transactional(readOnly = true)
    public synchronized void reloadVectorCache() {
        try {
            List<KnowledgeChunk> activeChunks = chunkRepository.findAllActiveWithRelations();
            inMemoryChunks.clear();
            inMemoryChunks.addAll(activeChunks);
            log.info("[RAG] Da nap thanh cong {} vector tri thuc vao bo nho RAM Cache!", inMemoryChunks.size());

            // Huấn luyện mô hình PCA 3D từ các vector có sẵn
            trainPcaModelFromActiveChunks();
        } catch (Exception e) {
            log.warn("[RAG] Khong the nap vector cache luc khoi dong: {}", e.getMessage());
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
     * Thuật toán Tìm kiếm Kết hợp (Hybrid Search: Dense Vector + Keyword Jaccard) theo Phân tầng
     */
    public RagQueryResponse hierarchicalSearch(String rawQuery, Long departmentId) {
        long startTime = System.currentTimeMillis();

        // 1. Chuẩn hóa từ viết tắt học vụ (avđr -> chuẩn ngoại ngữ đầu ra...)
        String expandedQuery = AcademicAbbreviationUtils.expand(rawQuery);
        float[] queryVector = geminiApiClient.getEmbedding(expandedQuery);
        Set<String> queryTokens = tokenize(expandedQuery);

        // 2. TẦNG 1: Quét trong kho Công văn / Quy chế chính thức (REGULATION)
        List<ScoredChunk> tier1Matches = new ArrayList<>();
        for (KnowledgeChunk chunk : inMemoryChunks) {
            if (!Boolean.TRUE.equals(chunk.getIsActive()) || !"REGULATION".equalsIgnoreCase(chunk.getSourceType())) {
                continue;
            }

            float[] chunkVec = chunk.getEmbeddingArray();
            if (chunkVec.length == 0 || chunkVec.length != queryVector.length) {
                continue;
            }

            // A. Dense Vector Cosine Similarity
            double denseScore = VectorMathUtils.cosineSimilarity(queryVector, chunkVec);

            // B. Sparse Keyword Boost (Thưởng điểm khi trùng từ khóa chính xác)
            double keywordScore = calculateTokenOverlap(queryTokens, chunk.getContent());
            double score = denseScore * (1.0 + 0.20 * keywordScore);

            // C. Phạt suy giảm thời gian (Time-decay)
            double timeWeight = calculateTimeWeight(chunk.getEffectiveYear());
            score *= timeWeight;

            // D. Ưu tiên theo Khoa/Phòng (Department Scope Boost)
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
            if (!Boolean.TRUE.equals(chunk.getIsActive()) || !"FAQ_CHAT".equalsIgnoreCase(chunk.getSourceType())) {
                continue;
            }

            float[] chunkVec = chunk.getEmbeddingArray();
            if (chunkVec.length == 0 || chunkVec.length != queryVector.length) {
                continue;
            }

            double denseScore = VectorMathUtils.cosineSimilarity(queryVector, chunkVec);
            double keywordScore = calculateTokenOverlap(queryTokens, chunk.getContent());
            double score = denseScore * (1.0 + 0.20 * keywordScore);

            double timeWeight = calculateTimeWeight(chunk.getEffectiveYear());
            score *= timeWeight;

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
                .needsHistoricalWarning(true) // Bắt buộc gắn cảnh báo đối chiếu thời gian
                .confidenceScore(bestScore)
                .executionTimeMs(elapsed)
                .matchedChunks(matches)
                .build();
    }

    public RagQueryResponse hierarchicalSearch(String rawQuery) {
        return hierarchicalSearch(rawQuery, null);
    }

    /**
     * Tính trọng số thời gian (Time-decay): 2026 = 1.0, 2025 = 0.85, 2024 = 0.70
     */
    private double calculateTimeWeight(Integer year) {
        if (year == null) return 0.70;
        if (year >= 2026) return 1.00;
        if (year == 2025) return 0.85;
        if (year == 2024) return 0.70;
        int age = Math.max(0, 2024 - year);
        return Math.max(0.50, 0.70 - (age * 0.05));
    }

    private double calculateTokenOverlap(Set<String> queryTokens, String content) {
        if (queryTokens.isEmpty() || content == null || content.isBlank()) {
            return 0.0;
        }
        Set<String> contentTokens = tokenize(content);
        long matches = queryTokens.stream().filter(contentTokens::contains).count();
        return (double) matches / queryTokens.size();
    }

    private Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("[^\\p{L}\\p{Nd}]+"))
                .filter(t -> t.length() > 1)
                .collect(Collectors.toSet());
    }

    /**
     * Xử lý nền (@Async) trích xuất văn bản từ PDF, băm chunk cấu trúc và tạo vector
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
                    log.info("[RAG] Da vo hieu hoa van ban cu #{} do duoc thay the boi #{}", supersededById, documentId);
                }
            }

            // 2. Trích xuất text từng trang
            File pdfFile = new File(document.getFilePath());
            List<PdfExtractorUtils.PageContent> pages = PdfExtractorUtils.extractPagesWithInfo(pdfFile);

            String firstPageText = pages.isEmpty() ? "" : pages.get(0).text();
            String docCode = PdfExtractorUtils.extractDocumentCode(firstPageText);
            String category = PdfExtractorUtils.detectCategory(document.getTitle(), firstPageText);

            document.setDocumentCode(docCode != null ? docCode : "Đang cập nhật");
            document.setCategory(category);
            document.setFileSize(pdfFile.length());
            document.setPageCount(pages.size());

            // 3. Phân mảnh văn bản cấu trúc có Header ngữ cảnh
            List<PdfExtractorUtils.StructuralChunk> structuralChunks = PdfExtractorUtils.chunkStructural(
                    pages,
                    document.getTitle(),
                    docCode,
                    document.getEffectiveYear(),
                    "HCMUTE"
            );

            // 4. Tạo vector embeddings và lưu vào DB
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<KnowledgeChunk> chunksToSave = new ArrayList<>();
            int chunkIndex = 1;

            for (PdfExtractorUtils.StructuralChunk sc : structuralChunks) {
                float[] embedding = geminiApiClient.getEmbedding(sc.injectedContent());
                String embeddingJson = mapper.writeValueAsString(embedding);

                KnowledgeChunk chunk = KnowledgeChunk.builder()
                        .document(document)
                        .title(document.getTitle() + " - Trang " + sc.pageNumber() + " (#" + chunkIndex++ + ")")
                        .content(sc.injectedContent())
                        .sourceType("REGULATION")
                        .effectiveYear(document.getEffectiveYear())
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

            document.setStatus("COMPLETED");
            document.setTotalChunks(chunksToSave.size());
            documentRepository.save(document);

            // Nạp lại RAM Cache
            reloadVectorCache();

            log.info("[RAG] Xu ly thanh cong tep PDF '{}', da tao {} chunks vector!", document.getTitle(), chunksToSave.size());
        } catch (Exception e) {
            log.error("[RAG] That bai khi xu ly tai lieu PDF #{}: {}", documentId, e.getMessage(), e);
            document.setStatus("FAILED");
            document.setErrorMessage(e.getMessage());
            documentRepository.save(document);
        }
    }

    /**
     * Xuất dữ liệu biểu diễn không gian 3D Vector WebGL (3D Force Graph)
     */
    public Map<String, Object> get3DVectorGraphData(String testQuery) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();

        if (pcaModel == null && !inMemoryChunks.isEmpty()) {
            trainPcaModelFromActiveChunks();
        }

        // Lấy tối đa 120 điểm tiêu biểu để WebGL hiển thị 60fps mượt mà
        int limit = Math.min(120, inMemoryChunks.size());
        for (int i = 0; i < limit; i++) {
            KnowledgeChunk c = inMemoryChunks.get(i);
            double[] coords = getOrComputePcaCoords(c);

            String color = "#4ade80"; // Xanh lục: Công văn 2026
            String group = "Công văn 2026";

            if ("FAQ_CHAT".equalsIgnoreCase(c.getSourceType())) {
                color = "#facc15"; // Vàng: FAQ
                group = "FAQ Tích lũy";
            } else if (c.getEffectiveYear() != null && c.getEffectiveYear() < 2026) {
                color = "#60a5fa"; // Xanh dương: Công văn cũ (2024-2025)
                group = "Công văn " + c.getEffectiveYear();
            }

            if (Boolean.TRUE.equals(c.getIsDeprecated()) || !Boolean.TRUE.equals(c.getIsActive())) {
                color = "#94a3b8"; // Xám: Hết hiệu lực
                group = "Lỗi thời / Tắt";
            }

            Map<String, Object> node = new HashMap<>();
            node.put("id", "chunk-" + c.getId());
            node.put("name", c.getTitle());
            node.put("group", group);
            node.put("color", color);
            node.put("year", c.getEffectiveYear());
            node.put("article", c.getArticleHeader() != null ? c.getArticleHeader() : "Điều khoản");
            node.put("x", coords[0]);
            node.put("y", coords[1]);
            node.put("z", coords[2]);
            node.put("val", 6);
            nodes.add(node);
        }

        // Nếu có câu hỏi thử nghiệm, chiếu câu hỏi thành node màu Đỏ và vẽ liên kết
        if (testQuery != null && !testQuery.isBlank() && pcaModel != null) {
            String expanded = AcademicAbbreviationUtils.expand(testQuery);
            float[] qVec = geminiApiClient.getEmbedding(expanded);
            double[] qCoords = VectorReductionUtils.project(qVec, pcaModel);

            String queryNodeId = "query-red";
            Map<String, Object> queryNode = new HashMap<>();
            queryNode.put("id", queryNodeId);
            queryNode.put("name", "Câu hỏi: " + testQuery);
            queryNode.put("group", "Query Hiện Tại");
            queryNode.put("color", "#ef4444"); // Đỏ nổi bật
            queryNode.put("x", qCoords[0]);
            queryNode.put("y", qCoords[1]);
            queryNode.put("z", qCoords[2]);
            queryNode.put("val", 12);
            nodes.add(queryNode);

            // Tìm Top 3 chunks gần nhất để nối đường link
            RagQueryResponse topMatches = hierarchicalSearch(testQuery);
            if (topMatches.getMatchedChunks() != null) {
                for (KnowledgeChunkMatchDto match : topMatches.getMatchedChunks()) {
                    Map<String, Object> link = new HashMap<>();
                    link.put("source", queryNodeId);
                    link.put("target", "chunk-" + match.getId());
                    link.put("similarity", match.getSimilarityScore());
                    link.put("color", "#ef4444");
                    links.add(link);
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("nodes", nodes);
        result.put("links", links);
        return result;
    }

    private synchronized void trainPcaModelFromActiveChunks() {
        List<float[]> vectors = inMemoryChunks.stream()
                .map(KnowledgeChunk::getEmbeddingArray)
                .filter(v -> v.length > 0)
                .limit(100)
                .collect(Collectors.toList());

        if (vectors.size() >= 3) {
            this.pcaModel = VectorReductionUtils.fit(vectors);
            log.info("[RAG] Da huan luyen thanh cong mo hinh PCA 3D tu {} vector mau!", vectors.size());
        }
    }

    private double[] getOrComputePcaCoords(KnowledgeChunk c) {
        if (c.getPcaX() != null && c.getPcaY() != null && c.getPcaZ() != null) {
            return new double[]{c.getPcaX(), c.getPcaY(), c.getPcaZ()};
        }
        if (pcaModel != null) {
            float[] vec = c.getEmbeddingArray();
            if (vec.length > 0) {
                double[] p = VectorReductionUtils.project(vec, pcaModel);
                c.setPcaX(p[0]);
                c.setPcaY(p[1]);
                c.setPcaZ(p[2]);
                return p;
            }
        }
        return new double[]{0.0, 0.0, 0.0};
    }

    /**
     * Vòng lặp tự học Human-in-the-loop: Chuyển đổi Ticket đã giải quyết thành FAQ Tri thức AI
     */
    @Transactional
    public KnowledgeChunk promoteTicketToFaq(Long ticketId) {
        if (ticketRepository == null) {
            throw new IllegalStateException("TicketRepository chưa được cấu hình");
        }
        var ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Ticket #" + ticketId));

        String question = ticket.getTitle() + "\n" + ticket.getDescription();
        String answer = (ticket.getFeedback() != null && !ticket.getFeedback().isBlank())
                ? ticket.getFeedback()
                : "Vấn đề đã được Cán bộ " + (ticket.getDepartment() != null ? ticket.getDepartment().getName() : "phụ trách") + " xử lý và giải quyết dứt điểm.";

        String fullContent = "[CÂU HỎI]: " + question + "\n[GIẢI ĐÁP TỪ CÁN BỘ]:\n" + answer;

        float[] embedding = geminiApiClient.getEmbedding(fullContent);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String embeddingJson;
        try {
            embeddingJson = mapper.writeValueAsString(embedding);
        } catch (Exception e) {
            embeddingJson = "[]";
        }

        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .title("FAQ [Ticket #" + ticket.getTicketCode() + "]: " + ticket.getTitle())
                .content(fullContent)
                .sourceType("FAQ_CHAT")
                .effectiveYear(Year.now().getValue())
                .priorityLevel(2)
                .department(ticket.getDepartment())
                .embeddingJson(embeddingJson)
                .isActive(true)
                .isDeprecated(false)
                .build();

        chunk.setCachedEmbedding(embedding);
        chunk = chunkRepository.save(chunk);

        // Đồng bộ lại RAM Cache
        reloadVectorCache();

        log.info("[Human-in-the-loop] Da nap thanh cong Ticket #{} vao kho tri thuc AI!", ticket.getTicketCode());
        return chunk;
    }

    private KnowledgeChunkMatchDto mapToMatchDto(ScoredChunk sc) {
        String deptName = sc.chunk.getDepartment() != null ? sc.chunk.getDepartment().getName() : "Toàn trường";
        String docCode = sc.chunk.getDocument() != null ? sc.chunk.getDocument().getDocumentCode() : null;
        String filePath = sc.chunk.getDocument() != null ? sc.chunk.getDocument().getFilePath() : null;

        return KnowledgeChunkMatchDto.builder()
                .id(sc.chunk.getId())
                .title(sc.chunk.getTitle())
                .content(sc.chunk.getContent())
                .sourceType(sc.chunk.getSourceType())
                .effectiveYear(sc.chunk.getEffectiveYear())
                .priorityLevel(sc.chunk.getPriorityLevel())
                .departmentName(deptName)
                .pageNumber(sc.chunk.getPageNumber())
                .articleHeader(sc.chunk.getArticleHeader())
                .documentCode(docCode)
                .filePath(filePath)
                .similarityScore(Math.round(sc.score * 10000.0) / 10000.0)
                .build();
    }

    private record ScoredChunk(KnowledgeChunk chunk, double score) {}
}
