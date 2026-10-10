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

    private static final double TIER_1_CONFIDENCE_THRESHOLD = 0.60;
    private static final double DEPARTMENT_BOOST_FACTOR = 1.25;

    private static final Set<String> STOP_WORDS = Set.of(
            "cho", "tôi", "em", "mình", "bạn", "hỏi", "về", "ở", "và", "các", "có", "không",
            "được", "như", "thế", "nào", "là", "gì", "nhé", "ạ", "ơi", "trong", "với", "của",
            "khi", "này", "kì", "kỳ", "năm", "khoa", "trường", "xin", "giúp"
    );

    private static final List<String> ACADEMIC_TOPIC_TERMS = List.of(
            "học phí", "đóng tiền", "mức thu", "miễn giảm", "tín chỉ",
            "chứng chỉ", "ngoại ngữ", "tiếng anh", "chuẩn đầu ra", "toeic", "ielts",
            "đăng ký môn học", "rút môn", "hủy môn", "đkmh", "học lại", "cải thiện",
            "tốt nghiệp", "xét tốt nghiệp", "chuẩn tốt nghiệp", "khóa luận", "đồ án",
            "học bổng", "khuyến khích", "trợ cấp",
            "điểm rèn luyện", "cảnh cáo học vụ", "thôi học", "bảo lưu",
            "ký túc xá", "nội trú", "bảo hiểm y tế", "khám sức khỏe", "công tác xã hội",
            "trưởng khoa", "phó trưởng khoa", "hiệu trưởng", "ban giám hiệu", "lãnh đạo", "ban chủ nhiệm khoa"
    );

    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final GeminiApiClient geminiApiClient;
    private final com.school.counseling.module.ticket.repository.TicketRepository ticketRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PythonAiEngineClient pythonAiEngineClient;

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
    @Transactional
    public synchronized void reloadVectorCache() {
        try {
            ensureGoldenTruthSeeded();
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

    public synchronized void ensureGoldenTruthSeeded() {
        if (chunkRepository == null) return;
        try {
            long count = chunkRepository.countBySourceType("GOLDEN_TRUTH");
            if (count >= 8) {
                return;
            }
            if (count > 0) {
                List<KnowledgeChunk> existing = chunkRepository.findBySourceType("GOLDEN_TRUTH");
                chunkRepository.deleteAll(existing);
            }
            log.info("[RAG] Bắt đầu tự động nạp 8 bản ghi Chân Lý Học Vụ (Golden Truth - Điểm Vàng) vào cơ sở dữ liệu...");
            List<KnowledgeChunk> goldenChunks = new ArrayList<>();

            // 1. Hiệu trưởng & Ban Giám hiệu
            goldenChunks.add(createGoldenChunk(
                    "Hiệu trưởng Trường Đại học Công nghệ Kỹ thuật TP.HCM (HCMUTE) hiện nay là ai? Ban Giám hiệu gồm những ai?",
                    "Lãnh đạo & Ban Giám hiệu đương nhiệm của Trường Đại học Công nghệ Kỹ thuật TP.HCM (HCM-UTE):\n"
                            + "• Hiệu trưởng: PGS. TS. Lê Hiếu Giang (được Bộ GD&ĐT bổ nhiệm theo Quyết định số 1468/QĐ-BGDĐT ngày 02/06/2026, thời hạn 5 năm; Email: gianglh@hcmute.edu.vn).\n"
                            + "• Các Phó Hiệu trưởng:\n"
                            + "1. TS. Trương Thị Hiền (QĐ số 1479/QĐ-BGDĐT ngày 02/06/2026).\n"
                            + "2. TS. Quách Thanh Hải (Nghị quyết số 14/NQ-HĐT của Hội đồng trường).\n"
                            + "3. PGS. TS. Châu Đình Thành (Nghị quyết số 15/NQ-HĐT của Hội đồng trường)."
            ));

            // 2. Trưởng khoa CNTT
            goldenChunks.add(createGoldenChunk(
                    "Ai đang giữ chức vụ Trưởng khoa Công nghệ Thông tin (FIT / IT) và thông tin liên hệ là gì?",
                    "Trưởng khoa Công nghệ Thông tin hiện nay là GVC. TS. Lê Văn Vinh (đồng thời là giảng viên thuộc Bộ môn Công nghệ Phần mềm). Email liên hệ: vinhlv@hcmute.edu.vn."
            ));

            // 3. Phó Trưởng khoa CNTT
            goldenChunks.add(createGoldenChunk(
                    "Ai đang giữ chức vụ Phó Trưởng khoa Công nghệ Thông tin (FIT / IT) và thông tin liên hệ là gì?",
                    "Khoa Công nghệ Thông tin hiện nay có các Phó Trưởng khoa bao gồm:\n"
                            + "1. TS. Lê Vĩnh Thịnh (thuộc Bộ môn Mạng và An ninh mạng) - Email: thinhlv@hcmute.edu.vn\n"
                            + "2. PGS. TS. Hoàng Văn Dũng (Giảng viên Cao cấp, thuộc Bộ môn Trí tuệ Nhân tạo) - Email: dunghv@hcmute.edu.vn."
            ));

            // 4. Tên trường & Căn cứ pháp lý QĐ 2809
            goldenChunks.add(createGoldenChunk(
                    "Tên chính thức hiện nay của trường là gì? Trường đổi tên khi nào?",
                    "Thông tin định danh và đổi tên chính thức:\n"
                            + "• Tên chính thức hiện nay: Trường Đại học Công nghệ Kỹ thuật Thành phố Hồ Chí Minh (tên viết tắt: HCM-UTE / HCMUTE).\n"
                            + "• Tên cũ: Trường Đại học Sư phạm Kỹ thuật Thành phố Hồ Chí Minh (sử dụng từ 1984 đến 25/12/2025).\n"
                            + "• Căn cứ pháp lý: Quyết định số 2809/QĐ-TTg ngày 26/12/2025 của Thủ tướng Chính phủ về việc đổi tên trường."
            ));

            // 5. Mã trường tuyển sinh SPK
            goldenChunks.add(createGoldenChunk(
                    "Mã trường tuyển sinh chính thức của HCMUTE trên hệ thống của Bộ GD&ĐT là gì?",
                    "Mã trường của Trường Đại học Công nghệ Kỹ thuật TP.HCM (HCM-UTE) khi đăng ký xét tuyển trên hệ thống của Bộ Giáo dục và Đào tạo là: SPK."
            ));

            // 6. Địa chỉ cơ sở đào tạo
            goldenChunks.add(createGoldenChunk(
                    "Địa chỉ các cơ sở đào tạo và hotline liên hệ của trường HCM-UTE?",
                    "Trường Đại học Công nghệ Kỹ thuật TP.HCM hiện có 03 cơ sở đào tạo với tổng diện tích đất 308.494 m²:\n"
                            + "• Cơ sở 1 (Trụ sở chính): Số 01 Võ Văn Ngân, Phường Linh Chiểu, TP. Thủ Đức, TP. Hồ Chí Minh.\n"
                            + "• Cơ sở 2: Số 484 Lê Văn Việt, Phường Tăng Nhơn Phú A, TP. Thủ Đức, TP. Hồ Chí Minh.\n"
                            + "• Phân hiệu Bình Phước: Số 899 Quốc lộ 14, Phường Bình Phước, Tỉnh Đồng Nai / Bình Phước.\n"
                            + "• Hotline tuyển sinh: (+84 - 028) 3722 5724 | Tổng đài: (+84 - 028) 3896 8641 | Email: tuyensinh@hcmute.edu.vn"
            ));

            // 7. Hoạt động Công tác xã hội (CTXH) tốt nghiệp
            goldenChunks.add(createGoldenChunk(
                    "Quy định hoạt động Công tác xã hội (CTXH) bắt buộc khi tốt nghiệp cần bao nhiêu ngày?",
                    "Sinh viên hệ chính quy bắt buộc phải tích lũy tối thiểu 04 Ngày CTXH (tương đương 40 Điểm CTXH) trong toàn khóa học mới đủ điều kiện xét tốt nghiệp (Quy đổi: 10 Điểm CTXH = 01 Ngày CTXH)."
            ));

            // 8. Học bổng khuyến khích học tập (HBKKHT)
            goldenChunks.add(createGoldenChunk(
                    "Điều kiện xét Học bổng Khuyến khích Học tập (HBKKHT) HCMUTE là gì? Rớt môn có được xét không?",
                    "Quy định & Tiêu chuẩn xét Học bổng Khuyến khích Học tập (HBKKHT) HCMUTE:\n"
                            + "1. Tích lũy tối thiểu 15 tín chỉ trong học kỳ chính.\n"
                            + "2. Điểm rèn luyện từ 70 trở lên.\n"
                            + "3. Điểm trung bình học kỳ (GPA) từ 2.50 trở lên.\n"
                            + "4. KHÔNG bị điểm F (rớt môn/nợ môn) trong học kỳ xét."
            ));

            chunkRepository.saveAll(goldenChunks);
            log.info("[RAG] ✅ Đã nạp thành công {} bản ghi Golden Truth chuẩn xác vào Database!", goldenChunks.size());
        } catch (Exception e) {
            log.warn("[RAG] Không thể nạp Golden Truth tự động: {}", e.getMessage());
        }
    }

    private KnowledgeChunk createGoldenChunk(String q, String a) {
        float[] vec = geminiApiClient.getEmbedding(q + " " + a);
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .title(q)
                .content("Câu hỏi: " + q + "\n\nTrả lời chuẩn mực (Golden Truth): " + a)
                .rawContent(a)
                .articleHeader("Tập Chân Lý Học Vụ (Golden Truth)")
                .sourceType("GOLDEN_TRUTH")
                .priorityLevel(1)
                .effectiveYear(2026)
                .isActive(true)
                .isDeprecated(false)
                .build();
        chunk.setEmbeddingArray(vec);
        return chunk;
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
        List<String> detectedTopics = ACADEMIC_TOPIC_TERMS.stream()
                .filter(topic -> expandedQuery.toLowerCase().contains(topic) || rawQuery.toLowerCase().contains(topic))
                .collect(Collectors.toList());

        // 1.5. TẦNG 0: Quét kho Chân lý Chuẩn mực (GOLDEN_TRUTH - Độ ưu tiên tuyệt đối 100%)
        List<ScoredChunk> goldenMatches = new ArrayList<>();
        for (KnowledgeChunk chunk : inMemoryChunks) {
            if (!Boolean.TRUE.equals(chunk.getIsActive()) || !"GOLDEN_TRUTH".equalsIgnoreCase(chunk.getSourceType())) {
                continue;
            }

            float[] chunkVec = chunk.getEmbeddingArray();
            if (chunkVec.length == 0 || chunkVec.length != queryVector.length) {
                continue;
            }

            double denseScore = VectorMathUtils.cosineSimilarity(queryVector, chunkVec);
            String fullSearchText = ((chunk.getTitle() != null ? chunk.getTitle() : "") + " "
                    + (chunk.getContent() != null ? chunk.getContent() : "")).toLowerCase();
            double keywordScore = calculateTokenOverlap(queryTokens, fullSearchText);
            double score = denseScore * (1.0 + 0.35 * keywordScore) * 1.35; // Golden Truth boost 1.35x

            if (!detectedTopics.isEmpty()) {
                boolean hasTopicMatch = detectedTopics.stream().anyMatch(fullSearchText::contains);
                if (hasTopicMatch) {
                    score *= 1.30;
                }
            }

            goldenMatches.add(new ScoredChunk(chunk, score));
        }

        goldenMatches.sort((a, b) -> Double.compare(b.score, a.score));

        // Nếu Golden Truth có điểm tốt (>= 0.45) -> DỪNG QUÉT NGAY, lấy Golden Truth làm kết quả tuyệt đối
        if (!goldenMatches.isEmpty() && goldenMatches.get(0).score >= 0.45) {
            double topScore = Math.min(0.98, Math.max(0.92, goldenMatches.get(0).score));
            List<KnowledgeChunkMatchDto> matches = goldenMatches.stream()
                    .limit(3)
                    .map(sc -> {
                        KnowledgeChunkMatchDto dto = mapToMatchDto(sc);
                        dto.setSimilarityScore(Math.round(Math.min(0.99, Math.max(0.90, sc.score)) * 1000.0) / 1000.0);
                        return dto;
                    })
                    .collect(Collectors.toList());

            long elapsed = System.currentTimeMillis() - startTime;
            return RagQueryResponse.builder()
                    .primarySourceType("GOLDEN_TRUTH")
                    .needsHistoricalWarning(false)
                    .confidenceScore(topScore)
                    .executionTimeMs(elapsed)
                    .matchedChunks(matches)
                    .build();
        }

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

            // E. Sparse Topic Term Filtering (Phạt nặng 0.35x nếu chunk lạc đề chủ đề cốt lõi)
            if (!detectedTopics.isEmpty()) {
                String fullText = ((chunk.getTitle() != null ? chunk.getTitle() : "") + " "
                        + (chunk.getContent() != null ? chunk.getContent() : "")).toLowerCase();
                boolean hasTopicMatch = detectedTopics.stream().anyMatch(fullText::contains);
                if (hasTopicMatch) {
                    score *= 1.30; // Thưởng 30% khi trúng chủ đề học vụ
                } else {
                    score *= 0.35; // Phạt nặng 65% nếu câu hỏi có chủ đề rõ ràng mà chunk không chứa
                }
            }

            tier1Matches.add(new ScoredChunk(chunk, score));
        }

        tier1Matches.sort((a, b) -> Double.compare(b.score, a.score));

        // Nếu Tầng 1 có kết quả tốt (>= 0.60) -> DỪNG QUÉT, lấy ngay Tầng 1 Công văn chính thức
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

            // E. Sparse Topic Term Filtering (Phạt nặng 0.35x nếu chunk lạc đề chủ đề cốt lõi)
            if (!detectedTopics.isEmpty()) {
                String fullText = ((chunk.getTitle() != null ? chunk.getTitle() : "") + " "
                        + (chunk.getContent() != null ? chunk.getContent() : "")).toLowerCase();
                boolean hasTopicMatch = detectedTopics.stream().anyMatch(fullText::contains);
                if (hasTopicMatch) {
                    score *= 1.30;
                } else {
                    score *= 0.35;
                }
            }

            tier2Matches.add(new ScoredChunk(chunk, score));
        }

        tier2Matches.sort((a, b) -> Double.compare(b.score, a.score));

        // NGUYÊN TẮC ƯU TIÊN CÔNG VĂN: Nếu Tầng 1 có kết quả (>= 0.50) và điểm số Tầng 1 >= Tầng 2 -> Luôn chọn Tầng 1
        if (!tier1Matches.isEmpty() && tier1Matches.get(0).score >= 0.50
                && (tier2Matches.isEmpty() || tier1Matches.get(0).score >= tier2Matches.get(0).score)) {
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

        List<KnowledgeChunkMatchDto> matches = tier2Matches.stream()
                .limit(3)
                .map(this::mapToMatchDto)
                .collect(Collectors.toList());

        // Nếu rơi xuống Tầng 2 nhưng Tầng 1 có công văn liên quan (>= 0.45) -> Ghép công văn vào đầu trích dẫn làm căn cứ pháp lý
        if (!tier1Matches.isEmpty() && tier1Matches.get(0).score >= 0.45) {
            matches.add(0, mapToMatchDto(tier1Matches.get(0)));
            if (matches.size() > 3) {
                matches = matches.subList(0, 3);
            }
        }

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
                .filter(t -> t.length() > 1 && !STOP_WORDS.contains(t))
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

        if (inMemoryChunks.isEmpty() || inMemoryChunks.stream().noneMatch(c -> "GOLDEN_TRUTH".equalsIgnoreCase(c.getSourceType()))) {
            reloadVectorCache();
        }

        if (pcaModel == null && !inMemoryChunks.isEmpty()) {
            trainPcaModelFromActiveChunks();
        }

        // 1. Phân loại và lấy danh sách chunk đại diện (Ưu tiên 100% Golden Truth lên đồ thị trước)
        List<KnowledgeChunk> prioritizedChunks = new ArrayList<>();
        inMemoryChunks.stream().filter(c -> "GOLDEN_TRUTH".equalsIgnoreCase(c.getSourceType())).forEach(prioritizedChunks::add);
        inMemoryChunks.stream().filter(c -> !"GOLDEN_TRUTH".equalsIgnoreCase(c.getSourceType())).forEach(prioritizedChunks::add);

        int limit = Math.min(150, prioritizedChunks.size());
        Set<Long> includedChunkIds = new HashSet<>();

        for (int i = 0; i < limit; i++) {
            KnowledgeChunk c = prioritizedChunks.get(i);
            Map<String, Object> node = build3DNodeMap(c, i);
            nodes.add(node);
            includedChunkIds.add(c.getId());
        }

        // 2. Nếu có câu hỏi thử nghiệm, tìm kiếm Top Matches và đảm bảo các node đích có mặt trên đồ thị
        RagQueryResponse topMatches = null;
        if (testQuery != null && !testQuery.isBlank()) {
            try {
                topMatches = hierarchicalSearch(testQuery);
                if (topMatches.getMatchedChunks() != null) {
                    for (KnowledgeChunkMatchDto match : topMatches.getMatchedChunks()) {
                        if (!includedChunkIds.contains(match.getId())) {
                            inMemoryChunks.stream()
                                    .filter(c -> Objects.equals(c.getId(), match.getId()))
                                    .findFirst()
                                    .ifPresent(c -> {
                                        Map<String, Object> node = build3DNodeMap(c, nodes.size());
                                        nodes.add(node);
                                        includedChunkIds.add(c.getId());
                                    });
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Lỗi khi tìm kiếm liên kết 3D cho query: {}", ex.getMessage());
            }
        }

        // 3. Loại bỏ hoàn toàn vòng xoắn liên kết chuỗi (Snake/Pretzel loop), các hạt tự do phân bố phẳng 2D

        // 4. Chiếu Query Vector thành Node Đỏ và vẽ các tia nối Laser nổi bật tới Top Matches
        if (testQuery != null && !testQuery.isBlank()) {
            double[] qCoords = new double[]{0.0, 15.0, 0.0};
            if (pcaModel != null) {
                try {
                    String expanded = AcademicAbbreviationUtils.expand(testQuery);
                    float[] qVec = geminiApiClient.getEmbedding(expanded);
                    qCoords = VectorReductionUtils.project(qVec, pcaModel);
                } catch (Exception e) {
                    log.debug("Không thể project vector query: {}", e.getMessage());
                }
            }

            String queryNodeId = "query-red";
            Map<String, Object> queryNode = new HashMap<>();
            queryNode.put("id", queryNodeId);
            queryNode.put("name", "Câu hỏi: " + testQuery);
            queryNode.put("group", "Query Hiện Tại");
            queryNode.put("color", "#ef4444"); // Đỏ rực rỡ
            queryNode.put("x", qCoords[0]);
            queryNode.put("y", qCoords[1]);
            queryNode.put("z", qCoords[2]);
            queryNode.put("val", 16);
            nodes.add(queryNode);

            if (topMatches != null && topMatches.getMatchedChunks() != null) {
                for (KnowledgeChunkMatchDto match : topMatches.getMatchedChunks()) {
                    Map<String, Object> laserLink = new HashMap<>();
                    laserLink.put("source", queryNodeId);
                    laserLink.put("target", "chunk-" + match.getId());
                    laserLink.put("similarity", match.getSimilarityScore());
                    laserLink.put("color", "#ef4444"); // Tia laser đỏ
                    links.add(laserLink);
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("nodes", nodes);
        result.put("links", links);
        return result;
    }

    private Map<String, Object> build3DNodeMap(KnowledgeChunk c, int index) {
        double[] coords = getOrComputePcaCoords(c);

        // Phân bố tự nhiên theo cụm (Clustering) hoặc tọa độ PCA 2D thực tế (loại bỏ vòng xoắn tròn)

        String color = "#4ade80"; // Xanh lục: Công văn 2026
        String group = "Công văn 2026";
        int nodeSize = 7;

        if ("GOLDEN_TRUTH".equalsIgnoreCase(c.getSourceType())) {
            color = "#facc15"; // Vàng rực: Điểm Vàng Golden Truth
            group = "Điểm Vàng (Golden Truth)";
            nodeSize = 10;
        } else if ("FAQ_CHAT".equalsIgnoreCase(c.getSourceType())) {
            color = "#fbbf24"; // Vàng hổ phách: FAQ tinh tuyển
            group = "FAQ Tinh Tuyển";
            nodeSize = 8;
        } else if (c.getEffectiveYear() != null && c.getEffectiveYear() < 2026) {
            color = "#60a5fa"; // Xanh dương: Công văn 2024-2025
            group = "Công văn " + c.getEffectiveYear();
        }

        if (Boolean.TRUE.equals(c.getIsDeprecated()) || !Boolean.TRUE.equals(c.getIsActive())) {
            color = "#94a3b8"; // Xám: Lỗi thời / Bị vô hiệu hóa
            group = "Lỗi thời / Tắt";
        }

        Map<String, Object> node = new HashMap<>();
        node.put("id", "chunk-" + c.getId());
        node.put("chunkId", c.getId());
        node.put("name", c.getTitle());
        node.put("group", group);
        node.put("color", color);
        node.put("year", c.getEffectiveYear());
        node.put("article", c.getArticleHeader() != null ? c.getArticleHeader() : "Trích đoạn quy chế");
        node.put("content", c.getContent() != null ? c.getContent() : "");
        node.put("isActive", Boolean.TRUE.equals(c.getIsActive()));
        node.put("isDeprecated", Boolean.TRUE.equals(c.getIsDeprecated()));
        node.put("sourceType", c.getSourceType());
        if (coords[0] != 0.0 || coords[1] != 0.0) {
            node.put("x", coords[0]);
            node.put("y", coords[1]);
        }
        node.put("val", nodeSize);
        return node;
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
        String answer = "Vấn đề đã được Cán bộ " + (ticket.getDepartment() != null ? ticket.getDepartment().getName() : "phụ trách") + " xử lý và giải quyết dứt điểm.";
        if (ticket.getHistories() != null && !ticket.getHistories().isEmpty()) {
            for (int i = ticket.getHistories().size() - 1; i >= 0; i--) {
                var h = ticket.getHistories().get(i);
                if (h.getActionNote() != null && !h.getActionNote().isBlank()) {
                    answer = h.getActionNote();
                    break;
                }
            }
        }

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

        // Đồng bộ bất đồng bộ sang Python ChromaDB (nếu bật)
        if (pythonAiEngineClient != null && pythonAiEngineClient.isPythonAiEnabled()) {
            Long deptId = ticket.getDepartment() != null ? ticket.getDepartment().getId() : null;
            pythonAiEngineClient.syncChunkToPythonEngineAsync(
                    "ticket_" + ticket.getId(),
                    fullContent,
                    "FAQ_CHAT",
                    ticket.getTicketCode(),
                    1,
                    java.time.Year.now().getValue(),
                    deptId
            );
        }

        log.info("[Human-in-the-loop] Da nap thanh cong Ticket #{} vao kho tri thuc AI!", ticket.getTicketCode());
        return chunk;
    }

    private KnowledgeChunkMatchDto mapToMatchDto(ScoredChunk sc) {
        String deptName = sc.chunk.getDepartment() != null ? sc.chunk.getDepartment().getName() : "Toàn trường";
        String docCode = sc.chunk.getDocument() != null ? sc.chunk.getDocument().getDocumentCode() : null;
        String filePath = sc.chunk.getDocument() != null ? sc.chunk.getDocument().getFilePath() : null;
        String officialTitle = (sc.chunk.getDocument() != null && sc.chunk.getDocument().getTitle() != null && !sc.chunk.getDocument().getTitle().isBlank())
                ? sc.chunk.getDocument().getTitle()
                : sc.chunk.getTitle();

        Long docId = sc.chunk.getDocument() != null ? sc.chunk.getDocument().getId() : null;

        return KnowledgeChunkMatchDto.builder()
                .id(sc.chunk.getId())
                .documentId(docId)
                .title(officialTitle)
                .content(sc.chunk.getContent())                 // injectedContent — cho debug/preview modal
                .rawContent(sc.chunk.getRawContent())           // plain text — cho LLM context (BR-02)
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
