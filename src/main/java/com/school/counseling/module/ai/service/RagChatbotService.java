package com.school.counseling.module.ai.service;

import com.school.counseling.module.ai.dto.KnowledgeChunkMatchDto;
import com.school.counseling.module.ai.dto.RagQueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service điều phối Chatbot AI RAG với các lớp phòng vệ (Guardrails) và bộ đệm Cache phản hồi.
 *
 * Cải tiến Sprint A+B:
 *  - BR-01/02: Context Isolation — dùng rawContent (plain text) thay injectedContent khi build LLM prompt
 *  - BR-06: Token Budget 2500 chars thay vì limit(3) cứng → không bỏ sót ngày tháng quan trọng
 *  - BR-07: suggestCreateTicket = true chỉ khi confidenceScore < SUGGEST_TICKET_THRESHOLD (0.45)
 *  - BR-08: isLlmGenerated = false khi rơi về fallback raw context
 *  - BR-10: Cache key NFC normalize + toLowerCase(Locale.ROOT) tránh cache miss do Unicode form
 *
 * Cải tiến Sprint Python Migration:
 *  - PY-01: Ưu tiên gọi Python AI Engine (cổng 8001) khi app.python-ai.enabled=true
 *  - PY-02: Tự động Fallback về Java RAG nếu Python Engine không khả dụng (zero downtime)
 *  - PY-03: Faithfulness score từ Python được ánh xạ vào RagQueryResponse
 */
@Slf4j
@Service
public class RagChatbotService {

    /** BR-07: Ngưỡng confidence để gợi ý sinh viên tạo Ticket thay vì tin tưởng câu trả lời AI */
    private static final double SUGGEST_TICKET_THRESHOLD = 0.45;

    /**
     * BR-06: Token budget cho LLM context (~2500 chars UTF-8 proxy ≈ 700 tokens tiếng Việt).
     * An toàn trong cửa sổ 8000-32000 tokens của Gemini Flash.
     */
    static final int CONTEXT_TOKEN_BUDGET_CHARS = 2500;

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            BẠN LÀ TRỢ LÝ HỌC VỤ AI CHÍNH THỨC CỦA TRƯỜNG ĐẠI HỌC SƯ PHẠM KỸ THUẬT TP.HCM (HCMUTE - QAUTE PORTAL).
            BẠN ĐANG HOẠT ĐỘNG DƯỚI CHẾ ĐỘ KIỂM SOÁT NGỮ CẢNH TỐI ĐA (STRICT ZERO-HALLUCINATION & CONTEXT ENFORCEMENT).

            ### NGUYÊN TẮC BẤT KHẢ XÂM PHẠM:
            1. CHỈ TRẢ LỜI DỰA TRÊN NGỮ CẢNH [THÔNG TIN NGỮ CẢNH ĐƯỢC TRÍCH XUẤT TỪ HỆ THỐNG]:
               - Chỉ được phép sử dụng các sự thật, số liệu, mốc thời gian, điều khoản nằm TRỰC TIẾP trong ngữ cảnh.
               - TUYỆT ĐỐI KHÔNG sử dụng kiến thức bên ngoài, không tự suy diễn, không tự tính toán ngoại suy nếu tài liệu không đề cập rõ ràng.

            2. QUY TẮC TỪ CHỐI TUYỆT ĐỐI (HARD REFUSAL):
               Nếu gặp một trong các tình huống sau:
               - Ngữ cảnh [THÔNG TIN NGỮ CẢNH ĐƯỢC TRÍCH XUẤT TỪ HỆ THỐNG] rỗng hoặc không liên quan trực tiếp đến câu hỏi của người dùng.
               - Điểm số khớp ngữ cảnh thấp hoặc chỉ khớp chung chung từ khóa nhưng sai nội dung.
               - Câu hỏi nằm ngoài các văn bản/quy chế được nạp.
               => BẠN BẮT BUỘC PHẢI TỪ CHỐI VÀ TRẢ LỜI NGUYÊN VĂN MẪU SAU:
               "Rất tiếc, thông tin này hiện chưa có trong cơ sở dữ liệu quy chế chính thức của nhà trường hoặc ngữ cảnh tra cứu chưa đủ xác thực.
               Bạn vui lòng:
               1. Kiểm tra lại các thông báo mới nhất trên mục [Bảng Tin Công Văn].
               2. Hoặc gửi yêu cầu qua [Phiếu Hỗ TrỢ (Ticket)] để Cán bộ Phòng/Khoa chuyên trách tiếp nhận và giải quyết trực tiếp."

            3. QUY TẮC TRÍCH DẪN NGUỒN CÔNG VĂN (CITATION):
               - Mọi câu trả lời hợp lệ BẮT BUỘC phải chỉ rõ căn cứ ở cuối văn bản theo định dạng:
                 📌 Căn cứ: [Tên văn bản / Số hiệu công văn] - Áp dụng năm: [Năm] - Điều/Mục (nếu có).
               - Tuyệt đối không trích dẫn tên văn bản chung chung hoặc bịa đặt số hiệu không tồn tại trong context.

            4. PHONG CÁCH DIỄN ĐẠT:
               - Trang trọng, súc tích, chuẩn mực môi trường sư phạm kỹ thuật. Đi thẳng vào trọng tâm, không chào hỏi dài dòng rườm rà.

            ---
            [THÔNG TIN NGỮ CẢNH ĐƯỢC TRÍCH XUẤT TỪ HỆ THỐNG]:
            %s
            """;

    private final RagKnowledgeService ragKnowledgeService;
    private final GeminiApiClient geminiApiClient;
    private final PythonAiEngineClient pythonAiEngineClient;
    private final DifyAiApiClient difyAiApiClient;
    private final com.school.counseling.module.feed.repository.PostRepository postRepository;

    // Response Cache — lưu câu trả lời cho các câu hỏi trùng lặp trong phiên chạy
    private final Map<String, RagQueryResponse> responseCache = new ConcurrentHashMap<>();

    @Autowired
    public RagChatbotService(RagKnowledgeService ragKnowledgeService,
                             GeminiApiClient geminiApiClient,
                             PythonAiEngineClient pythonAiEngineClient,
                             @Autowired(required = false) DifyAiApiClient difyAiApiClient,
                             @Autowired(required = false)
                             com.school.counseling.module.feed.repository.PostRepository postRepository) {
        this.ragKnowledgeService = ragKnowledgeService;
        this.geminiApiClient = geminiApiClient;
        this.pythonAiEngineClient = pythonAiEngineClient;
        this.difyAiApiClient = difyAiApiClient;
        this.postRepository = postRepository;
    }

    public RagChatbotService(RagKnowledgeService ragKnowledgeService,
                             GeminiApiClient geminiApiClient,
                             PythonAiEngineClient pythonAiEngineClient) {
        this(ragKnowledgeService, geminiApiClient, pythonAiEngineClient, null, null);
    }

    /**
     * Backward-compatible overload không cần sessionId.
     */
    public RagQueryResponse ask(String question, Long departmentId) {
        return ask(question, departmentId, null);
    }

    /**
     * Xử lý câu hỏi của sinh viên và tổng hợp phản hồi RAG an toàn.
     *
     * @param question     Câu hỏi thô của sinh viên
     * @param departmentId ID phòng ban để ưu tiên boost (null = toàn trường)
     * @param sessionId    Session ID cuộc hội thoại (phục vụ multi-turn — Sprint C)
     */
    public RagQueryResponse ask(String question, Long departmentId, String sessionId) {
        return ask(question, departmentId, sessionId, null);
    }

    /**
     * Xử lý câu hỏi của sinh viên và tổng hợp phản hồi RAG an toàn với tùy chọn Engine (Dify / Gemini).
     *
     * @param question        Câu hỏi thô của sinh viên
     * @param departmentId    ID phòng ban để ưu tiên boost (null = toàn trường)
     * @param sessionId       Session ID cuộc hội thoại (phục vụ multi-turn — Sprint C)
     * @param preferredEngine Engine ưu tiên ("DIFY" hoặc "GEMINI", null = mặc định)
     */
    public RagQueryResponse ask(String question, Long departmentId, String sessionId, String preferredEngine) {
        if (question == null || question.isBlank()) {
            return RagQueryResponse.builder()
                    .answer("Xin chào! Bạn có thể đặt câu hỏi về học vụ, học phí, xét tốt nghiệp để tôi hỗ trợ.")
                    .llmGenerated(false)
                    .build();
        }

        // TẦNG 0: Lọc siêu ngắn (≤ 2 ký tự) — 0ms, 0 token
        String trimmed = question.trim();
        if (trimmed.length() <= 2) {
            return RagQueryResponse.builder()
                    .answer("Dạ, bạn vui lòng đặt câu hỏi rõ ràng hơn để tôi hỗ trợ học vụ chính xác nhất nhé! 📚")
                    .llmGenerated(false).build();
        }

        String normalizedQ = Normalizer.normalize(question.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);

        // 1. Chitchat & Greeting Filter (Semantic Router) — Phản hồi tức thì 0ms, 0 token
        if (isGreetingOrChitchat(normalizedQ)) {
            return RagQueryResponse.builder()
                    .answer("Chào bạn! Tôi là Trợ lý Cố vấn Học vụ AI của Trường Đại học Sư phạm Kỹ thuật TP.HCM (HCMUTE) 🎓.\n\n"
                            + "Tôi sẵn sàng hỗ trợ bạn tra cứu và giải đáp các vấn đề:\n"
                            + "• 📋 **Quy chế học vụ:** Đăng ký môn học (ĐKMH), chuyển điểm, chuẩn đầu ra (AVĐR), xét tốt nghiệp...\n"
                            + "• 💰 **Học phí & Học bổng:** Hạn nộp, tài khoản nộp học phí, tiêu chuẩn học bổng khuyến khích...\n"
                            + "• 🏆 **Điểm rèn luyện (ĐRL):** Quy định đánh giá, thời hạn nộp minh chứng...\n"
                            + "• 🏫 **Thông tin Nhà trường:** Địa chỉ 2 cơ sở, hotline liên hệ, 11 khoa đào tạo...\n\n"
                            + "Bạn đang cần hỗ trợ vấn đề gì, hãy nhập câu hỏi bên dưới nhé!")
                    .confidenceScore(1.0)
                    .llmGenerated(false)
                    .build();
        }

        // 1.1 Laughter / Cười cợt ("haha", "hihi", "hehe", "huhu", "lol")
        if (isLaughterOrEmotion(normalizedQ)) {
            return RagQueryResponse.builder()
                    .answer("Haha, rất vui được trò chuyện cùng bạn! 😄 Tôi là Trợ lý Cố vấn Học vụ HCMUTE.\n\nNếu bạn đang cần giải đáp bất kỳ quy chế học vụ, học phí, lịch thi hay thủ tục tốt nghiệp nào, hãy đặt câu hỏi để tôi hỗ trợ bạn nhé!")
                    .confidenceScore(1.0)
                    .llmGenerated(false)
                    .matchedChunks(List.of())
                    .build();
        }

        // 1.2 Profanity / Từ ngữ thiếu chuẩn mực ("con cho", "ngu", v.v.)
        if (isProfanityOrInappropriate(normalizedQ)) {
            return RagQueryResponse.builder()
                    .answer("Dạ, QAUTE Portal là hệ thống hỗ trợ học vụ chính thức và văn minh của HCMUTE 🎓.\n\nBạn vui lòng sử dụng ngôn từ lịch sự, chuẩn mực môi trường sư phạm để tôi có thể hỗ trợ giải đáp tốt nhất cho bạn nhé!")
                    .confidenceScore(1.0)
                    .llmGenerated(false)
                    .matchedChunks(List.of())
                    .build();
        }

        // 1.3 Lời cảm ơn, chào tạm biệt ("cảm ơn", "thanks", "ok", "tạm biệt")
        if (isGratitudeOrClosing(normalizedQ)) {
            return RagQueryResponse.builder()
                    .answer("Dạ không có gì ạ! Rất vui được hỗ trợ bạn 🎓. Chúc bạn có một học kỳ thật nhiều kết quả tốt tại HCMUTE. Nếu cần thêm thông tin học vụ gì, bạn cứ nhắn cho tôi nhé!")
                    .confidenceScore(1.0)
                    .llmGenerated(false)
                    .matchedChunks(List.of())
                    .build();
        }

        // 2. Direct Match từ Cơ sở Tri thức Vàng HCMUTE (Golden Truth) — Không ảo giác 100%
        String goldenAnswer = matchGoldenTruth(normalizedQ);
        if (goldenAnswer != null) {
            return RagQueryResponse.builder()
                    .answer(goldenAnswer)
                    .confidenceScore(1.0)
                    .llmGenerated(false)
                    .build();
        }

        // 3. Off-topic Chitchat Guardrail — Từ chối câu hỏi ngoài lề và định hướng về học vụ
        if (isOffTopicChitchat(normalizedQ)) {
            return RagQueryResponse.builder()
                    .answer("Dạ tôi là Trợ lý Cố vấn Học vụ AI của HCMUTE 🎓. Tôi chỉ hỗ trợ giải đáp các vấn đề liên quan đến quy chế học vụ, học phí, điểm rèn luyện, đăng ký môn học và thủ tục đào tạo của Trường.\n\nBạn vui lòng đặt câu hỏi cụ thể về học vụ để tôi hỗ trợ chính xác nhất nhé!")
                    .confidenceScore(1.0)
                    .llmGenerated(false)
                    .matchedChunks(List.of())
                    .build();
        }

        // BR-10: NFC normalize + Locale.ROOT toLowerCase để không bị cache miss do Unicode form
        String engineTag = preferredEngine != null ? preferredEngine.toUpperCase(Locale.ROOT) : "AUTO";
        String cacheKey = normalizedQ + "_" + (departmentId != null ? departmentId : 0) + "_" + engineTag;

        if (responseCache.containsKey(cacheKey)) {
            log.debug("[RAG Cache] Cache hit cho câu hỏi: '{}' (engine={})", question, engineTag);
            return responseCache.get(cacheKey);
        }

        boolean forceGemini = "GEMINI".equalsIgnoreCase(preferredEngine);

        // === DIFY-01: Ưu tiên Cloud Dify AI Chatflow (nếu được bật và người dùng không ép dùng Gemini) ===
        if (!forceGemini && difyAiApiClient != null && difyAiApiClient.isDifyEnabled()) {
            RagQueryResponse difyResult = askViaDify(question, departmentId);
            if (difyResult != null && difyResult.getAnswer() != null && !difyResult.getAnswer().isBlank()) {
                log.info("[RAG] ✅ Phản hồi thành công từ Cloud Dify AI Chatflow.");
                if (responseCache.size() < 1000) {
                    responseCache.put(cacheKey, difyResult);
                }
                return difyResult;
            }
            log.warn("[RAG] ⚠️ Dify AI bị nghẽn/timeout/lỗi, tự động Fallback xuống tầng tiếp theo (Python/Gemini)...");
        }

        // === PY-01: Ưu tiên gọi Python AI Engine (nếu được bật) ===
        if (pythonAiEngineClient.isPythonAiEnabled()) {
            RagQueryResponse pythonResult = askViaPythonEngine(question, departmentId);
            if (pythonResult != null) {
                // Đảm bảo luôn có matchedChunks công văn pháp quy để hiển thị Provenance
                if (pythonResult.getMatchedChunks() == null || pythonResult.getMatchedChunks().isEmpty()) {
                    try {
                        var localSearch = ragKnowledgeService.hierarchicalSearch(question, departmentId);
                        if (localSearch != null && localSearch.getMatchedChunks() != null) {
                            pythonResult.setMatchedChunks(localSearch.getMatchedChunks());
                        }
                    } catch (Exception ignored) {}
                }

                enrichMatchedChunksWithPostAndDocLinks(pythonResult.getMatchedChunks());

                // Python thành công → cache và trả về luôn
                if (responseCache.size() < 1000) {
                    responseCache.put(cacheKey, pythonResult);
                }
                return pythonResult;
            }
            // PY-02: Python không khả dụng → tiếp tục fallback Java RAG bên dưới
            log.warn("[RAG] Python Engine không phản hồi, fallback về Java RAG.");
        }

        // === Java RAG Pipeline (legacy — fallback hoặc khi python-ai.enabled=false) ===

        // 1. Tìm kiếm phân tầng trong RAM
        RagQueryResponse searchResult = ragKnowledgeService.hierarchicalSearch(question, departmentId);

        // Academic Guardrail: Siêu siết chặt ngưỡng kiểm duyệt (Strict Zero-Hallucination)
        // Nếu confidence score < 0.50 hoặc không có matchedChunks -> từ chối suy diễn, hướng dẫn tạo ticket hoặc xem Bảng tin
        if (searchResult.getConfidenceScore() < 0.90 || searchResult.getMatchedChunks() == null || searchResult.getMatchedChunks().isEmpty()) {
            searchResult.setAnswer("Rất tiếc, thông tin này hiện chưa có trong cơ sở dữ liệu quy chế chính thức của nhà trường hoặc ngữ cảnh tra cứu chưa đủ xác thực (điểm tương đồng < 90%).\n\n"
                    + "Bạn vui lòng:\n"
                    + "1. 📋 Kiểm tra lại các thông báo mới nhất trên mục **[Bảng Tin Công Văn](/feed/official)**.\n"
                    + "2. 🎫 Hoặc gửi yêu cầu qua **[Phiếu Hỗ Trợ (Ticket)](/tickets/create)** để Cán bộ Phòng/Khoa chuyên trách tiếp nhận và giải quyết trực tiếp.");
            searchResult.setMatchedChunks(List.of());
            searchResult.setSuggestCreateTicket(true);
            searchResult.setLlmGenerated(false);
            return searchResult;
        }

        // 2. BR-02 + BR-06: Build context từ rawContent trong token budget
        String contextString = buildContextString(searchResult.getMatchedChunks());

        // 3. Gọi Gemini tổng hợp câu trả lời (trả về [answer, isLlmGenerated])
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, contextString);
        String[] resultPair = geminiApiClient.generateChatResponseWithFlag(systemPrompt,
                "Câu hỏi của sinh viên: " + question);
        String generatedAnswer = (resultPair != null && resultPair.length > 0) ? resultPair[0] : "";
        boolean isLlmGenerated = resultPair != null && resultPair.length > 1 && Boolean.parseBoolean(resultPair[1]);

        // Cải tiến: Nếu LLM bận và rơi vào fallback, trình bày câu trả lời ngắn gọn, không dump raw chunk thô
        if (!isLlmGenerated && generatedAnswer != null && generatedAnswer.contains("Máy chủ AI đang tạm bận")) {
            generatedAnswer = "📋 **Quy chế học vụ liên quan trích xuất từ văn bản chính thức của Nhà trường:**\n\n"
                    + "Hệ thống đã xác định công văn quy chế điều chỉnh nội dung bạn hỏi. Vui lòng xem thông tin chi tiết qua **Huy hiệu Nguồn** hoặc mở bài viết trên **Bảng tin** bên dưới.\n\n"
                    + "💡 *Nếu cần giải thích thêm, bạn có thể bấm nút Gửi Ticket để Cán bộ Phòng ban chuyên trách hỗ trợ trực tiếp.*";
        }

        // 4. Chỉ prepend ⚠️ banner khi rơi vào Tầng 2 VÀ câu trả lời thực sự từ LLM
        if (searchResult.isNeedsHistoricalWarning() && isLlmGenerated) {
            generatedAnswer = "⚠️ **Lưu ý:** Câu trả lời dưới đây dựa trên dữ liệu lịch sử tư vấn các năm trước. Bạn nên đối chiếu với quy chế năm học 2026 hoặc liên hệ phòng ban chuyên trách để xác nhận lại.\n\n"
                    + generatedAnswer;
        }

        // 5. BR-07: Chỉ gợi ý Ticket khi AI thực sự không đủ thông tin
        boolean suggest = searchResult.getConfidenceScore() < SUGGEST_TICKET_THRESHOLD
                || searchResult.getMatchedChunks() == null
                || searchResult.getMatchedChunks().isEmpty();

        searchResult.setAnswer(generatedAnswer);
        searchResult.setSuggestCreateTicket(suggest);
        searchResult.setLlmGenerated(isLlmGenerated);

        // Nối link bài viết trên Bảng tin cho các chunk khớp
        enrichMatchedChunksWithPostAndDocLinks(searchResult.getMatchedChunks());

        log.info("[RAG Java] Q='{}' source={} score={:.4f} llm={} ticket={}",
                question.length() > 50 ? question.substring(0, 50) + "..." : question,
                searchResult.getPrimarySourceType(),
                searchResult.getConfidenceScore(),
                isLlmGenerated, suggest);

        // Lưu vào Cache (max 1000 entries)
        if (responseCache.size() < 1000) {
            responseCache.put(cacheKey, searchResult);
        }

        return searchResult;
    }

    /**
     * PY-01: Gọi Python AI Engine và chuyển đổi response sang RagQueryResponse.
     * Trả về null nếu Python không khả dụng (để trigger fallback).
     */
    private RagQueryResponse askViaPythonEngine(String question, Long departmentId) {
        try {
            PythonAiEngineClient.PythonChatResponse pyResponse =
                    pythonAiEngineClient.askPythonEngine(question, departmentId, false);

            if (pyResponse == null) return null;

            log.info("[RAG Python] Q='{}' score={:.4f} llm={} ticket={} time={}ms",
                    question.length() > 50 ? question.substring(0, 50) + "..." : question,
                    pyResponse.confidence_score(), pyResponse.llm_generated(),
                    pyResponse.suggest_create_ticket(), pyResponse.execution_time_ms());

            List<KnowledgeChunkMatchDto> pythonMatches = new ArrayList<>();
            if (pyResponse.matched_chunks() != null && !pyResponse.matched_chunks().isEmpty()) {
                for (var pm : pyResponse.matched_chunks()) {
                    pythonMatches.add(KnowledgeChunkMatchDto.builder()
                            .title(pm.source())
                            .content(pm.content())
                            .rawContent(pm.content())
                            .sourceType(pyResponse.source_type())
                            .documentCode(pm.document_code())
                            .pageNumber(pm.page_number())
                            .effectiveYear(pm.effective_year())
                            .similarityScore(pm.score())
                            .build());
                }
            }

            return RagQueryResponse.builder()
                    .answer(pyResponse.reply())
                    .primarySourceType(pyResponse.source_type())
                    .confidenceScore(pyResponse.confidence_score())
                    .suggestCreateTicket(pyResponse.suggest_create_ticket())
                    .llmGenerated(pyResponse.llm_generated())
                    .executionTimeMs(pyResponse.execution_time_ms())
                    .needsHistoricalWarning(pyResponse.needs_historical_warning())
                    .matchedChunks(pythonMatches)
                    .build();

        } catch (Exception e) {
            log.error("[RAG Python] Lỗi khi chuyển đổi response: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * DIFY-01: Gửi câu hỏi đến Cloud Dify AI Chatflow.
     * Trả về null nếu Dify không phản hồi hoặc bị nghẽn (để kích hoạt fallback).
     */
    private RagQueryResponse askViaDify(String question, Long departmentId) {
        try {
            var difyResp = difyAiApiClient.sendChatMessage(question, "student-user", null);
            if (difyResp == null || difyResp.answer() == null || difyResp.answer().isBlank()) {
                return null;
            }

            var builder = RagQueryResponse.builder()
                    .answer(difyResp.answer())
                    .primarySourceType("DIFY_AI")
                    .confidenceScore(0.95)
                    .llmGenerated(true)
                    .suggestCreateTicket(false);

            // Nối thêm matchedChunks từ kho tri thức nội bộ để hiển thị Huy hiệu Nguồn (CHỈ KHI SCORE >= 0.50)
            try {
                var localSearch = ragKnowledgeService.hierarchicalSearch(question, departmentId);
                if (localSearch != null && localSearch.getMatchedChunks() != null && localSearch.getConfidenceScore() >= 0.50) {
                    List<KnowledgeChunkMatchDto> validChunks = localSearch.getMatchedChunks().stream()
                            .filter(c -> c.getSimilarityScore() >= 0.50)
                            .collect(java.util.stream.Collectors.toList());
                    builder.matchedChunks(validChunks);
                } else {
                    builder.matchedChunks(List.of());
                }
            } catch (Exception ignored) {}

            var res = builder.build();
            if (res.getMatchedChunks() != null && !res.getMatchedChunks().isEmpty()) {
                enrichMatchedChunksWithPostAndDocLinks(res.getMatchedChunks());
            }
            return res;
        } catch (Exception e) {
            log.warn("[RAG Dify] Lỗi khi xử lý response từ Dify: {}", e.getMessage());
            return null;
        }
    }

    /**
     * BR-02 + BR-06: Build context string từ rawContent theo token budget.
     *
     * Dùng rawContent (plain text) thay injectedContent để LLM không bị nhiễu bởi
     * header kỹ thuật [VĂN BẢN:...|NỘI DUNG:...].
     * Dừng nạp chunk khi tổng chars vượt CONTEXT_TOKEN_BUDGET_CHARS.
     */
    String buildContextString(List<KnowledgeChunkMatchDto> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "Không tìm thấy văn bản quy chế trực tiếp liên quan.\n";
        }

        StringBuilder ctx = new StringBuilder();
        int totalChars = 0;
        int includedCount = 0;

        for (KnowledgeChunkMatchDto match : chunks) {
            // BR-02: Ưu tiên rawContent sạch, fallback strip header cho legacy chunks cũ chưa có rawContent
            String displayText = (match.getRawContent() != null && !match.getRawContent().isBlank())
                    ? match.getRawContent()
                    : stripInjectedHeader(match.getContent());

            String entry = buildChunkEntry(match, displayText);

            // BR-06: Bỏ hẳn chunk này nếu làm vượt budget (không cắt giữa câu)
            if (totalChars + entry.length() > CONTEXT_TOKEN_BUDGET_CHARS && includedCount > 0) {
                log.debug("[RAG Context] Budget {}chars đạt sau {} chunks, bỏ chunk còn lại",
                        CONTEXT_TOKEN_BUDGET_CHARS, includedCount);
                break;
            }

            ctx.append(entry);
            totalChars += entry.length();
            includedCount++;
        }

        log.debug("[RAG Context] {} chunks, {} chars (budget={})", includedCount, totalChars, CONTEXT_TOKEN_BUDGET_CHARS);
        return ctx.toString();
    }

    private String buildChunkEntry(KnowledgeChunkMatchDto match, String displayText) {
        StringBuilder sb = new StringBuilder();
        // Header metadata ngắn gọn, thân thiện với LLM (không phải format kỹ thuật [VĂN BẢN:...])
        sb.append("[Nguồn: ").append(match.getTitle() != null ? match.getTitle() : "Công văn HCMUTE");
        if (match.getDocumentCode() != null) sb.append(" | Số: ").append(match.getDocumentCode());
        if (match.getEffectiveYear() != null) sb.append(" | Năm: ").append(match.getEffectiveYear());
        if (match.getPageNumber() != null) sb.append(" | Trang: ").append(match.getPageNumber());
        sb.append("]\n").append(displayText).append("\n\n");
        return sb.toString();
    }

    /**
     * BR-09: Strip header kỹ thuật khỏi injectedContent cho legacy chunks chưa có rawContent.
     * Pattern: "[VĂN BẢN: ...][NỘI DUNG]:\n<plain text>"
     */
    String stripInjectedHeader(String content) {
        if (content == null) return "";
        String stripped = content.replaceAll("(?s)\\[VĂN BẢN:.*?\\]\\s*\\[NỘI DUNG\\]:\\s*\n?", "");
        // Xóa số trang đơn lẻ ở đầu (vd "15 5. Lịch sinh hoạt..." → "5. Lịch sinh hoạt...")
        stripped = stripped.replaceAll("^\\d+\\s+(?=\\d)", "");
        return stripped.trim();
    }

    /**
     * Nhận diện tiếng cười hoặc biểu lộ cảm xúc thân thiện
     */
    boolean isLaughterOrEmotion(String q) {
        if (q == null || q.isBlank()) return false;
        String clean = q.replaceAll("[\\p{Punct}\\s]+", " ").trim();
        if (hasAcademicKeywords(clean)) return false;
        // Tiếng cười cơ bản
        if (clean.matches("^(ha+|hi+|he+|hơ+|ho+|ka+|khu+|huhu|kkk|kaka|lol|lmao|vcl|vl|cl|ảo thật|hay quá|tuyệt vời)(\\s+(quá|qua|luôn|luon|thế|the|nhỉ|nhi|vậy|vay))*$"))
            return true;
        if (clean.matches("^(haha|hihi|hehe|hoho|kaka|huhu|kkk|lol|lmao|vcl|vl)$"))
            return true;
        // Brainrot / Gen Z slang (mở rộng)
        return clean.matches(".*(skibidi|sigma|gyatt|mewing|rizzler|fanum tax|ohio|grimace shake|baby john|chudjak|soyjak|wojak|mogging|looksmaxxing|edge lord|gooner|hawk tuah|smurf rank|clutch|gank|ping cao|lag giật|toxic|noob pro|gg wp|afk|buff bẩn|aura farming|bá khí|wibu rách|tung tung sahur|baby john khóc|grimace shake|bop yes|chúa tể bóng đêm|yah yead|u là trời|ét ô ét|ố dề|lemỏn|gATO|trà xanh tiểu tam|trúng tim đen|bla bla blo|he he ha ha hô|kaka kiki kuku|simp lỏ|cuckold|alpha male|beta male|não tàn|brainrot nặng|nút chuối|bay màu|xu cà na|bánh mì hoa cúc|ảo ma canada|cái nịt|quay xe gấp|grimace|gâu gâu ẳng ẳng|meo meo méo).*");
    }

    /**
     * Nhận diện từ ngữ thô tục / thiếu chuẩn mực học đường
     */
    boolean isProfanityOrInappropriate(String q) {
        if (q == null || q.isBlank()) return false;
        String clean = q.toLowerCase(Locale.ROOT).trim();
        // Từ thô tục cơ bản
        if (clean.matches(".*(con chó|con cho|chó đẻ|cho de|đĩ|con đĩ|ngu vcl|ngu thế|óc chó|oc cho|đm|dm|dkm|đkm|vcl|đéo|mẹ mày|me may|khốn nạn|cút|biến đi).*"))
            return true;
        if (clean.matches("^(chó|cho|ngu|cút|đm|dm|dkm|đkm|vcl|vl|cl)$"))
            return true;
        // Profanity mở rộng / brainrot độc hại
        return clean.matches(".*(ngu lồn|thiểu năng|não tàn|mất trí|ncl tht|đ hiểu kiểu|vãi cả chưởng|clgt|đách quan tâm|kệ mẹ|mịa nó|simp lỏ|cuckold|mọc sừng|con lợn lười|chết tiệt|bực mình vcl|cạn ngôn|xà lơ tầm bậy|cứu vớt linh hồn|dẹp đi nghỉ khỏe|xong đời cô lựu|toang thật|j z tr cj|ak s cũng đc|vln vcc vler|đmm cmnr|vãi chưởng|lạy hồn).*");
    }

    /**
     * Nhận diện lời cảm ơn, chào tạm biệt hoặc xác nhận
     */
    boolean isGratitudeOrClosing(String q) {
        if (q == null || q.isBlank()) return false;
        String clean = q.replaceAll("[\\p{Punct}\\s]+", " ").trim();
        if (hasAcademicKeywords(clean)) return false;
        return clean.matches("^(cảm ơn|cam on|thanks|thank you|tks|tkss|tks ad|cảm ơn bot|cam on bot|cảm ơn bạn|cam on ban|cảm ơn thầy|cảm ơn cô|ok|oke|okie|dạ|da|vâng|rõ rồi|hiểu rồi|chúc ngủ ngon|tạm biệt|bye|goodbye)(\\s+(nhiều|nhieu|nha|nhé|nhe|ạ|a))*$");
    }

    /**
     * Nhận diện câu chào hỏi thuần túy (Semantic Router)
     */
    boolean isGreetingOrChitchat(String q) {
        if (q == null || q.isBlank()) return true;
        String clean = q.replaceAll("[\\p{Punct}\\s]+", " ").trim();

        // Nếu câu hỏi có chứa từ khóa học vụ trọng tâm thì KHÔNG coi là chitchat thuần túy
        if (hasAcademicKeywords(clean)) {
            return false;
        }

        // Lời chào hỏi linh hoạt
        if (clean.matches("^(alo|alô|chào|chao|xin chào|xin chao|hi|hello|hey|hé lô|he lo|good morning)(\\s+(bot|ad|admin|bạn|ban|thầy|thay|cô|co|ơi|oi|nha|nhé|nhe|ạ|a))*$")) {
            return true;
        }

        // Câu hỏi về danh tính bot hoặc yêu cầu trợ giúp
        if (clean.matches("^(bạn là ai|ban la ai|mày là ai|may la ai|cậu là ai|cau la ai|bot là gì|bot la gi|ai đây|ai day|trợ giúp|tro giup|hướng dẫn|huong dan)$")) {
            return true;
        }

        return false;
    }

    /**
     * Nhận diện các câu hỏi chitchat/ngoài lề (Off-topic) không thuộc phạm vi tư vấn học vụ
     */
    boolean isOffTopicChitchat(String q) {
        if (q == null || q.isBlank()) return false;
        String clean = q.replaceAll("[\\p{Punct}\\s]+", " ").trim();

        // Nếu có từ khóa học vụ thì ưu tiên xử lý học vụ
        if (hasAcademicKeywords(clean)) {
            return false;
        }

        // Các chủ đề tán gẫu ngoài lề
        return clean.matches(".*(kể chuyện|chuyện cười|hát|làm thơ|chơi game|người yêu|yêu không|thời tiết|ăn gì|uống gì|xem phim|nghe nhạc|tán gái|tán gẫu|chém gió|bói toán|xem bói|tử vi|bao nhiêu tuổi|ở đâu đấy|mấy tuổi).*");
    }

    /**
     * Kiểm tra sự xuất hiện của từ khóa học vụ cốt lõi
     */
    boolean hasAcademicKeywords(String text) {
        return text.matches(".*(đkmh|đrl|avđr|cđr|ctđt|gdqp|học phí|hoc phi|học bổng|hoc bong|đăng ký|dang ky|môn học|mon hoc|học phần|hoc phan|tốt nghiệp|tot nghiep|chuyển điểm|chuyen diem|điểm|diem|cảnh cáo|canh cao|thôi học|thoi hoc|bảo lưu|bao luu|nghỉ học|nghi hoc|quy chế|quy che|công văn|cong van|thông báo|thong bao|khoa|phòng ban|phong ban|tín chỉ|tin chi|nguyện vọng|nguyen vong|xét tuyển|xet tuyen|spk|hcmute|học lại|hoc lai|cải thiện|cai thien).*");
    }

    /**
     * Trích xuất câu trả lời chuẩn xác tuyệt đối từ Cơ sở dữ liệu chuẩn HCMUTE (Golden Truth)
     */
    String matchGoldenTruth(String q) {
        if (q == null || q.isBlank()) return null;
        String clean = q.replaceAll("[\\p{Punct}\\s]+", " ").trim().toLowerCase();

        // 1. Tên trường, Đổi tên & Căn cứ pháp lý
        if ((clean.matches(".*(tên trường|ten truong|đổi tên|doi ten|tên mới|ten moi|tên cũ|ten cu).*")
                && clean.matches(".*(trường|truong|spkt|hcmute).*"))
                || clean.contains("2809")) {
            return "🏛️ **Thông tin định danh và đổi tên chính thức:**\n\n"
                    + "• **Tên chính thức hiện nay:** **Trường Đại học Công nghệ Kỹ thuật Thành phố Hồ Chí Minh** (tên viết tắt: **HCM-UTE** / **HCMUTE**).\n"
                    + "• **Tên cũ:** Trường Đại học Sư phạm Kỹ thuật Thành phố Hồ Chí Minh (sử dụng từ 1984 đến 25/12/2025).\n"
                    + "• **Căn cứ pháp lý:** **Quyết định số 2809/QĐ-TTg ngày 26/12/2025 của Thủ tướng Chính phủ** về việc đổi tên Trường Đại học Sư phạm Kỹ thuật Thành phố Hồ Chí Minh thành Trường Đại học Công nghệ Kỹ thuật Thành phố Hồ Chí Minh.";
        }

        // 2. Hiệu trưởng & Ban Giám hiệu đương nhiệm
        if (clean.matches(".*(hiệu trưởng|hieu truong|ban giám hiệu|ban giam hieu|phó hiệu trưởng|pho hieu truong).*")) {
            return "👨‍🏫 **Lãnh đạo & Ban Giám hiệu đương nhiệm của Nhà trường:**\n\n"
                    + "• **Hiệu trưởng:** PGS. TS. **Lê Hiếu Giang** (được Bộ GD&ĐT bổ nhiệm theo Quyết định số 1468/QĐ-BGDĐT ngày 02/06/2026, thời hạn 5 năm; Email: `gianglh@hcmute.edu.vn`).\n"
                    + "• **Các Phó Hiệu trưởng:**\n"
                    + "  1. TS. **Trương Thị Hiền** (Quyết định số 1479/QĐ-BGDĐT ngày 02/06/2026).\n"
                    + "  2. TS. **Quách Thanh Hải** (Nghị quyết số 14/NQ-HĐT của Hội đồng trường).\n"
                    + "  3. PGS. TS. **Châu Đình Thành** (Nghị quyết số 15/NQ-HĐT của Hội đồng trường).";
        }

        // 3. Mã trường tuyển sinh
        if (clean.matches(".*(mã trường|ma truong|mã tuyển sinh|ma tuyen sinh|mã xét tuyển|ma xet tuyen).*")
                || (clean.contains("nguyện vọng") && clean.contains("mã"))) {
            return "🎯 **Mã trường tuyển sinh chính thức:**\n\n"
                    + "Mã trường của Trường Đại học Công nghệ Kỹ thuật TP.HCM (HCM-UTE) khi đăng ký xét tuyển trên hệ thống của Bộ Giáo dục và Đào tạo là: **SPK**.";
        }

        // 4. Địa chỉ cơ sở & Phân hiệu
        if (clean.matches(".*(địa chỉ|dia chi|mấy cơ sở|may co so|bao nhiêu cơ sở|bao nhieu co so|ở đâu|o dau|trụ sở|tru so|hotline|số điện thoại|so dien thoai|email hỗ trợ|email ho tro).*")
                && (clean.contains("trường") || clean.contains("truong") || clean.contains("hcmute") || clean.contains("spkt") || clean.matches(".*(địa chỉ|dia chi|mấy cơ sở|hotline).*"))) {
            return "📍 **Thông tin liên hệ & Các cơ sở đào tạo của HCM-UTE:**\n\n"
                    + "Trường Đại học Công nghệ Kỹ thuật TP.HCM hiện có **03 cơ sở đào tạo** với tổng diện tích đất 308.494 m²:\n"
                    + "• **Cơ sở 1 (Trụ sở chính):** Số 01 Võ Văn Ngân, Phường Linh Chiểu, TP. Thủ Đức, TP. Hồ Chí Minh.\n"
                    + "• **Cơ sở 2:** Số 484 Lê Văn Việt, Phường Tăng Nhơn Phú A, TP. Thủ Đức, TP. Hồ Chí Minh.\n"
                    + "• **Phân hiệu Bình Phước:** Số 899 Quốc lộ 14, Phường Bình Phước, Tỉnh Đồng Nai / Bình Phước (thành lập theo QĐ 1576/QĐ-BGDĐT).\n"
                    + "• 📞 **Hotline tuyển sinh:** (+84 - 028) 3722 5724 | **Tổng đài:** (+84 - 028) 3896 8641\n"
                    + "• 🌐 **Website:** [https://hcmute.edu.vn](https://hcmute.edu.vn) | **Email:** tuyensinh@hcmute.edu.vn";
        }

        // 5. Danh sách các khoa và viện đào tạo
        if (clean.matches(".*(các khoa|cac khoa|danh sách khoa|danh sach khoa|bao nhiêu khoa|co bao nhieu khoa).*")
                || (clean.contains("khoa") && (clean.contains("hcmute") || clean.contains("trường")))) {
            return "🏫 **Cơ cấu tổ chức đào tạo tại HCM-UTE:**\n\n"
                    + "Nhà trường hiện có **13 Khoa chuyên môn** + **2 Viện** + **1 Trung tâm** đào tạo trọng điểm:\n"
                    + "1. **Khoa Cơ khí Chế tạo máy (FME):** Chế tạo máy, Cơ khí, Robot & Trí tuệ nhân tạo (AI).\n"
                    + "2. **Khoa Cơ khí Động lực (FAE):** Công nghệ Ô tô, Nhiệt - Điện lạnh, Kỹ thuật Hàng không.\n"
                    + "3. **Khoa Điện - Điện tử (FEE):** Kỹ thuật Điện, Điện tử Viễn thông, Tự động hóa, Kỹ thuật Y sinh, IoT.\n"
                    + "4. **Khoa Công nghệ Thông tin (FIT):** Khoa học Máy tính, CN Phần mềm, An toàn TT, Kỹ thuật Dữ liệu, Trí tuệ Nhân tạo.\n"
                    + "5. **Khoa Xây dựng (FCE):** Kỹ thuật Xây dựng, Công trình Giao thông, Quản lý Xây dựng.\n"
                    + "6. **Khoa Công nghệ Hóa học và Thực phẩm (FCFT):** Hóa học, Thực phẩm, Môi trường.\n"
                    + "7. **Khoa Kinh tế (FE):** Quản trị Kinh doanh, Kế toán, Thương mại Điện tử, Logistics, Tài chính.\n"
                    + "8. **Khoa Ngoại ngữ (FFL):** Ngôn ngữ Anh, Sư phạm Tiếng Anh.\n"
                    + "9. **Khoa Thời trang và Du lịch (FFT):** Công nghệ May, Thiết kế Thời trang, Dịch vụ Ăn uống & Du lịch.\n"
                    + "10. **Khoa Khoa học Ứng dụng (FAS):** Thiết kế Đồ họa, Kiến trúc, Kiến trúc Nội thất.\n"
                    + "11. **Khoa In và Truyền thông:** Kỹ thuật In, Thiết kế Đa phương tiện.\n"
                    + "12. **Khoa Chính trị và Luật:** Ngành Luật, Luật Kinh tế, Lý luận Chính trị.\n"
                    + "13. **Khoa Đào tạo Tiên tiến:** Chương trình song ngữ và 100% tiếng Anh.\n"
                    + "• **Viện Sư phạm Kỹ thuật (ITE)** & **Viện Đào tạo Quốc tế (OIE)**\n"
                    + "• **Trung tâm GDQP&AN** (Giáo dục Quốc phòng và An ninh).";
        }

        // 6. Trưởng khoa & Phó Trưởng khoa Công nghệ Thông tin (FIT / IT / CNTT)
        if (clean.matches(".*(trưởng khoa|truong khoa).*") && clean.matches(".*(cntt|công nghệ thông tin|cong nghe thong tin|fit|it|khoa it).*")) {
            if (clean.matches(".*(phó|pho).*")) {
                return "💻 **Phó Trưởng khoa Công nghệ Thông tin (FIT):**\n\n"
                        + "Khoa Công nghệ Thông tin hiện nay có các Phó Trưởng khoa bao gồm:\n"
                        + "1. **TS. Lê Vĩnh Thịnh** (thuộc Bộ môn Mạng và An ninh mạng) - Email: `thinhlv@hcmute.edu.vn`\n"
                        + "2. **PGS. TS. Hoàng Văn Dũng** (Giảng viên Cao cấp, thuộc Bộ môn Trí tuệ Nhân tạo) - Email: `dunghv@hcmute.edu.vn`.";
            }
            return "💻 **Trưởng khoa Công nghệ Thông tin (FIT):**\n\n"
                    + "Trưởng khoa Công nghệ Thông tin hiện nay là **GVC. TS. Lê Văn Vinh** (đồng thời là giảng viên thuộc Bộ môn Công nghệ Phần mềm). Email liên hệ: `vinhlv@hcmute.edu.vn`.";
        }

        // 7. Quy định Công tác xã hội (CTXH) tốt nghiệp
        if (clean.matches(".*(ctxh|công tác xã hội|cong tac xa hoi).*")
                && clean.matches(".*(tốt nghiệp|tot nghiep|bao nhiêu|điều kiện|dieu kien).*")) {
            return "🤝 **Quy định Hoạt động Công tác xã hội (CTXH) bắt buộc khi tốt nghiệp:**\n\n"
                    + "Sinh viên hệ chính quy bắt buộc phải tích lũy tối thiểu **04 Ngày CTXH (tương đương 40 Điểm CTXH)** trong toàn khóa học mới đủ điều kiện xét tốt nghiệp (Quy đổi: 10 Điểm CTXH = 01 Ngày CTXH).";
        }

        // 8. Phương thức xét tuyển
        if (clean.matches(".*(phương thức xét tuyển|phuong thuc xet tuyen|xét tuyển như thế nào|xet tuyen nhu the nao|các cách xét tuyển).*")) {
            return "🎯 **Các phương thức xét tuyển chính thức của HCM-UTE:**\n\n"
                    + "Hằng năm, trường áp dụng các phương thức tuyển sinh chủ ngạch bao gồm:\n"
                    + "1. **Phương thức 1:** Xét tuyển dựa trên kết quả kỳ thi Tốt nghiệp THPT.\n"
                    + "2. **Phương thức 2:** Xét tuyển bằng học bạ THPT (Điểm trung bình 5 học kỳ hoặc 6 học kỳ).\n"
                    + "3. **Phương thức 3:** Xét tuyển dựa trên kết quả kỳ thi ĐGNL của Đại học Quốc gia TP.HCM.\n"
                    + "4. **Phương thức 4:** Xét tuyển thẳng và ưu tiên xét tuyển theo quy chế của Bộ GD&ĐT (học sinh giỏi trường chuyên, chứng chỉ quốc tế IELTS, SAT...).";
        }

        // 10. Học bổng khuyến khích học tập (HBKKHT) & Điều kiện xét
        if (clean.matches(".*(học bổng|hoc bong|hbkkht).*")
                && clean.matches(".*(điều kiện|dieu kien|xét|xet|tiêu chuẩn|tieu chuan|tiêu chí|tieu chi|thế nào|the nao|bao nhiêu điểm|rớt môn|rot mon|nợ môn|no mon).*")) {
            return "🎓 **Quy định & Tiêu chuẩn Xét Học bổng Khuyến khích Học tập (HBKKHT) HCMUTE:**\n\n"
                    + "Căn cứ Quy chế Công tác Sinh viên và Quản lý Học bổng của Nhà trường, sinh viên được xét cấp HBKKHT khi đáp ứng đầy đủ các điều kiện sau trong học kỳ xét:\n\n"
                    + "1. **Số tín chỉ tối thiểu:** Đăng ký và tích lũy tối thiểu **15 tín chỉ** trong học kỳ chính (không tính học kỳ hè và các môn Giáo dục Thể chất, GDQP&AN).\n"
                    + "2. **Điểm rèn luyện (ĐRL):** Đạt từ loại **Khá (70 điểm)** trở lên.\n"
                    + "3. **Điểm trung bình học kỳ (ĐTBHK):** Đạt từ **2.50 / 4.0** (hoặc 7.0 / 10.0) trở lên:\n"
                    + "   • Loại Khá: ĐTB ≥ 2.50 & ĐRL ≥ 70\n"
                    + "   • Loại Giỏi: ĐTB ≥ 3.20 & ĐRL ≥ 80\n"
                    + "   • Loại Xuất sắc: ĐTB ≥ 3.60 & ĐRL ≥ 90\n"
                    + "4. **Quy định về Rớt môn:** **TUYỆT ĐỐI KHÔNG có học phần nào bị điểm F (hoặc dưới 5.0 thang 10) trong học kỳ xét.** (Rớt bất kỳ môn nào sẽ không đủ điều kiện xét HBKKHT kỳ đó).\n"
                    + "5. **Kỷ luật:** Không bị kỷ luật từ mức Khiển trách trở lên trong học kỳ xét.";
        }

        // 11. Quy định Rớt môn, Học lại & Học cải thiện điểm
        if (clean.matches(".*(rớt môn|rot mon|học lại|hoc lai|cải thiện|cai thien|điểm f|diem f|thi lại|thi lai).*")) {
            return "📚 **Quy định về Rớt môn (Điểm F), Học lại và Học cải thiện điểm:**\n\n"
                    + "• **Rớt môn (Điểm F < 4.0 thang 10 hoặc < 1.0 thang 4):**\n"
                    + "  - Nếu là môn học bắt buộc: Bắt buộc phải đăng ký học lại môn đó ở các học kỳ tiếp theo cho đến khi đạt điểm D trở lên.\n"
                    + "  - Nếu là môn học tự chọn: Có thể đăng ký học lại môn đó hoặc chọn học phần tự chọn khác tương đương trong cùng khối kiến thức.\n\n"
                    + "• **Học cải thiện điểm (Nâng điểm GPA):**\n"
                    + "  - Sinh viên được phép đăng ký học lại các môn đã đạt điểm D, D+, C, C+ để cải thiện điểm số.\n"
                    + "  - Điểm học cải thiện sẽ thay thế điểm cũ khi tính điểm trung bình tích lũy (lấy kết quả cao nhất).\n\n"
                    + "• **Đăng ký:** Đăng ký trực tuyến trên hệ thống `dkmh.hcmute.edu.vn` trong các đợt mở cổng đầu học kỳ.";
        }

        // 12. Chuẩn Ngoại ngữ đầu ra (AVĐR) & Xét tốt nghiệp
        if (clean.matches(".*(avđr|avdr|chuẩn đầu ra|chuan dau ra|ngoại ngữ|ngoai ngu|tiếng anh|tieng anh|toeic|vstep|ielts).*")
                && clean.matches(".*(tốt nghiệp|tot nghiep|điều kiện|dieu kien|bao nhiêu|bao nhieu|nộp chứng chỉ|nop chung chi).*")) {
            return "🌐 **Quy định Chuẩn đầu ra Ngoại ngữ (AVĐR) xét Tốt nghiệp HCMUTE:**\n\n"
                    + "Căn cứ Quy định Chuẩn trình độ Ngoại ngữ áp dụng cho sinh viên chính quy:\n\n"
                    + "• **Chương trình Đại trà (Khối ngành Kỹ thuật / Công nghệ):**\n"
                    + "  - TOEIC Quốc tế (Nghe - Đọc): Tối thiểu **500 điểm** (hoặc VSTEP Bậc 3 / B1, IELTS 4.5).\n\n"
                    + "• **Chương trình Chất lượng cao (CLC):**\n"
                    + "  - CLC tiếng Việt: Tối thiểu **TOEIC 550 - 600 điểm** (hoặc VSTEP Bậc 3+ / B1).\n"
                    + "  - CLC tiếng Anh: Tối thiểu **TOEIC 650 điểm** (hoặc IELTS 5.5, VSTEP Bậc 4 / B2).\n\n"
                    + "• **Khối ngành Kinh tế / Ngôn ngữ Anh:**\n"
                    + "  - Ngành Ngôn ngữ Anh: Đạt C1 VSTEP / IELTS 6.5 trở lên.\n\n"
                    + "• **Thủ tục nộp:** Nộp chứng chỉ trực tuyến qua Cổng học vụ và đối chiếu bản gốc tại Khoa Ngoại ngữ (Phòng A1-306) trước đợt xét tốt nghiệp ít nhất 30 ngày.";
        }

        // 13. Cảnh báo học vụ & Buộc thôi học
        if (clean.matches(".*(cảnh báo học vụ|canh bao hoc vu|cảnh cáo|canh cao|buộc thôi học|buoc thoi hoc|bị đuổi học|bi duoi hoc).*")) {
            return "⚠️ **Quy chế Cảnh báo Học vụ & Buộc thôi học tại HCMUTE:**\n\n"
                    + "• **Cảnh báo học vụ (CBHV) bị áp dụng khi:**\n"
                    + "  1. Điểm trung bình học kỳ (ĐTBHK) < 1.00 (học kỳ đầu) hoặc < 1.20 (các học kỳ tiếp theo).\n"
                    + "  2. Điểm trung bình tích lũy (ĐTBTL) < 1.60 (năm 1), < 1.80 (năm 2), < 2.00 (năm 3 trở đi).\n"
                    + "  3. Tổng số tín chỉ nợ vượt quá quy định.\n\n"
                    + "• **Buộc thôi học:**\n"
                    + "  - Bị cảnh báo học vụ **02 lần liên tiếp** hoặc **03 lần không liên tiếp** trong toàn khóa học.\n"
                    + "  - Vượt quá thời gian tối đa được phép học tập tại trường (Khóa 4 năm tối đa 8 năm).\n\n"
                    + "📌 *Sinh viên bị cảnh báo học vụ cần liên hệ ngay Cố vấn học tập của Khoa để lập kế hoạch cải thiện điểm.*";
        }

        // 14. Bảo lưu kết quả học tập & Tạm dừng học
        if (clean.matches(".*(bảo lưu|bao luu|tạm dừng|tam dung|nghỉ học tạm thời|nghi hoc tam thoi).*")) {
            return "📝 **Quy định về Bảo lưu & Tạm dừng học tập:**\n\n"
                    + "• **Điều kiện được bảo lưu:**\n"
                    + "  1. Được điều động thực hiện nghĩa vụ quân sự.\n"
                    + "  2. Bị ốm đau, tai nạn hoặc thai sản có xác nhận của cơ sở y tế có thẩm quyền.\n"
                    + "  3. Vì nhu cầu cá nhân: Phải học ít nhất **01 học kỳ** tại trường và không thuộc diện bị buộc thôi học.\n\n"
                    + "• **Thời hạn bảo lưu:** Mỗi lần tạm dừng không quá **02 học kỳ chính**; tổng thời gian tạm dừng không vượt quá thời gian tối đa của khóa học.\n"
                    + "• **Hồ sơ thủ tục:** Nộp Đơn xin tạm dừng học tập tại Phòng Đào tạo (Phòng A1-201) trước khi học kỳ mới bắt đầu ít nhất 02 tuần.";
        }


        // 15. HCMUTE / HCM-UTE là trường gì (câu hỏi cơ bản nhất — hay bị lọt xuống RAG với score thấp)
        if (clean.matches(".*(hcmute la gi|hcmute là gì|hcmute la truong gi|truong gi|spkt la gi|spkt là gì|ute la truong gi|ute là trường gì).*")
                || (clean.length() <= 30 && clean.matches(".*(hcmute|spkt|ute).*") && clean.matches(".*(là|la|gì|gi|nào|nao).*"))) {
            return "🏛️ **Trường Đại học Công nghệ Kỹ thuật TP.HCM (HCM-UTE / HCMUTE) là gì?**\n\n"
                    + "• **Tên đầy đủ hiện tại:** Trường Đại học Công nghệ Kỹ thuật Thành phố Hồ Chí Minh.\n"
                    + "• **Tên viết tắt:** HCM-UTE (quốc tế) / HCMUTE (thông dụng).\n"
                    + "• **Tên cũ (trước 25/12/2025):** Trường Đại học Sư phạm Kỹ thuật TP.HCM (SPKT).\n"
                    + "• **Loại hình:** Trường đại học công lập trọng điểm, trực thuộc Bộ Giáo dục & Đào tạo.\n"
                    + "• **Địa chỉ chính:** Số 01 Võ Văn Ngân, Phường Linh Chiểu, TP. Thủ Đức, TP.HCM.\n"
                    + "• **Website:** https://hcmute.edu.vn | 📞 Hotline: (028) 3722-5724.";
        }

        // 16. Điểm rèn luyện (ĐRL) — cách tính, thang điểm
        if (clean.matches(".*(drl|điểm rèn luyện|diem ren luyen|tính drl|tinh drl|drl tinh|xep loai ren luyen|xếp loại rèn luyện|cach tinh drl|cách tính drl|drl ntn|drl the nao|drl tinh diem ntn).*")) {
            return "📊 **Cách tính Điểm Rèn Luyện (ĐRL) tại HCMUTE:**\n\n"
                    + "ĐRL được đánh giá theo thang điểm **100** qua **5 tiêu chí** mỗi học kỳ:\n\n"
                    + "| # | Tiêu chí | Điểm tối đa |\n"
                    + "|---|---------|------------|\n"
                    + "| 1 | Ý thức học tập, nghiên cứu khoa học | 20 đ |\n"
                    + "| 2 | Ý thức chấp hành quy chế, nội quy | 25 đ |\n"
                    + "| 3 | Hoạt động chính trị - xã hội, VHVN | 20 đ |\n"
                    + "| 4 | Phẩm chất công dân, quan hệ cộng đồng | 25 đ |\n"
                    + "| 5 | Ý thức tham gia công tác lớp/đoàn thể | 10 đ |\n\n"
                    + "**Xếp loại ĐRL:**\n"
                    + "• 🏆 Xuất sắc: ≥ 90 điểm\n"
                    + "• ✅ Tốt: 80 – 89 điểm\n"
                    + "• 🆗 Khá: 65 – 79 điểm\n"
                    + "• ⚠️ Trung bình: 50 – 64 điểm\n"
                    + "• ❌ Yếu/Kém: < 50 điểm\n\n"
                    + "📌 *Nộp minh chứng ĐRL trực tuyến trên Cổng học vụ trong vòng 2 tuần đầu học kỳ tiếp theo.*";
        }

        // 17. Học phí — nộp ở đâu, tài khoản ngân hàng
        if (clean.matches(".*(dong hoc phi|đóng học phí|nop hoc phi|nộp học phí|stk truong|tài khoản nộp|tai khoan nop|cach nop hoc phi|cách nộp học phí|hoc phi o dau|học phí ở đâu|thanh toan hoc phi|thanh toán học phí).*")) {
            return "💳 **Hướng dẫn nộp Học phí tại HCMUTE:**\n\n"
                    + "Sinh viên có thể nộp học phí qua **3 hình thức**:\n\n"
                    + "1. **Nộp trực tuyến** qua Cổng học vụ tại [https://hcmute.edu.vn](https://hcmute.edu.vn) → Mục **Tài chính - Học phí**.\n"
                    + "2. **Chuyển khoản ngân hàng** vào tài khoản chính thức của Nhà trường:\n"
                    + "   - Cú pháp chuyển khoản: **[MSSV] - [Họ và tên] - Nộp học phí**.\n"
                    + "   - *Thông tin tài khoản cụ thể xem tại Thông báo học phí hàng học kỳ trên Bảng tin.*\n"
                    + "3. **Nộp trực tiếp** tại Phòng Tài vụ (Phòng A1-106), Cơ sở 1, giờ hành chính.\n\n"
                    + "📌 *Thời hạn nộp học phí và mức học phí từng ngành: xem Thông báo chính thức trên [Bảng tin Công văn](/feed/official).*";
        }

        return null;
    }

    /**
     * Nối liên kết bài đăng trên Bảng tin cho các chunk công văn trùng khớp
     */
    void enrichMatchedChunksWithPostAndDocLinks(List<KnowledgeChunkMatchDto> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        for (KnowledgeChunkMatchDto chunk : chunks) {
            if (chunk.getPostId() == null && postRepository != null) {
                try {
                    String title = chunk.getTitle();
                    String docCode = chunk.getDocumentCode();

                    // 1. Thử tìm theo số hiệu văn bản (documentCode)
                    if (docCode != null && !docCode.isBlank() && !docCode.equalsIgnoreCase("Đang cập nhật")) {
                        var postOpt = postRepository.findFirstByTitleContainingAndIsDeletedFalse(docCode.trim());
                        if (postOpt.isPresent()) {
                            chunk.setPostId(postOpt.get().getId());
                            continue;
                        }
                    }

                    // 2. Thử tìm theo cleanTitle (bỏ đuôi .pdf, .md, ký hiệu scan Signed...)
                    if (title != null && !title.isBlank()) {
                        String cleanTitle = title.replaceAll("(?i)\\.(pdf|md)$", "")
                                .replaceAll("(?i)\\s*-\\s*Signed\\s*\\(\\d+\\)", "")
                                .trim();
                        var postOpt = postRepository.findFirstByTitleContainingAndIsDeletedFalse(cleanTitle);
                        if (postOpt.isPresent()) {
                            chunk.setPostId(postOpt.get().getId());
                            continue;
                        }

                        // 3. Nếu cleanTitle dài, thử tìm theo 30 ký tự đầu tiên
                        if (cleanTitle.length() > 30) {
                            String prefix = cleanTitle.substring(0, 30).trim();
                            var prefixPostOpt = postRepository.findFirstByTitleContainingAndIsDeletedFalse(prefix);
                            if (prefixPostOpt.isPresent()) {
                                chunk.setPostId(prefixPostOpt.get().getId());
                                continue;
                            }
                        }

                        // 4. Thử tìm theo số đầu tiên của tên file (vd "108" trong "108 cvdi tb...")
                        var m = java.util.regex.Pattern.compile("^(\\d{2,4})\\b").matcher(cleanTitle);
                        if (m.find()) {
                            String numCode = m.group(1);
                            var numPostOpt = postRepository.findFirstByTitleContainingAndIsDeletedFalse(numCode);
                            if (numPostOpt.isPresent()) {
                                chunk.setPostId(numPostOpt.get().getId());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[RAG] Khong the tim postId tuong ung cho chunk: {}", e.getMessage());
                }
            }
        }
    }
}
