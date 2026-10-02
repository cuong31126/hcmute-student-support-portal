package com.school.counseling.module.ai.service;

import com.school.counseling.module.ai.dto.KnowledgeChunkMatchDto;
import com.school.counseling.module.ai.dto.RagQueryResponse;
import com.school.counseling.module.feed.entity.Post;
import com.school.counseling.module.feed.repository.PostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class RagChatbotServiceTest {

    private RagKnowledgeService ragKnowledgeService;
    private GeminiApiClient geminiApiClient;
    private PythonAiEngineClient pythonAiEngineClient;
    private PostRepository postRepository;
    private RagChatbotService chatbotService;

    @BeforeEach
    void setUp() {
        ragKnowledgeService = Mockito.mock(RagKnowledgeService.class);
        geminiApiClient = Mockito.mock(GeminiApiClient.class);
        pythonAiEngineClient = Mockito.mock(PythonAiEngineClient.class);
        postRepository = Mockito.mock(PostRepository.class);

        // Python AI engine disabled by default in unit test
        when(pythonAiEngineClient.isPythonAiEnabled()).thenReturn(false);

        chatbotService = new RagChatbotService(
                ragKnowledgeService,
                geminiApiClient,
                pythonAiEngineClient,
                postRepository
        );
    }

    @Test
    @DisplayName("Giai đoạn 5A: Chitchat greeting router phản hồi tức thì với 'alo' hoặc 'xin chào'")
    void ask_greetingQuestion_returnsImmediateChitchatWithoutCallingLlm() {
        RagQueryResponse res1 = chatbotService.ask("alo", null);
        assertNotNull(res1);
        assertFalse(res1.isLlmGenerated());
        assertTrue(res1.getAnswer().contains("Trường Đại học Sư phạm Kỹ thuật TP.HCM (HCMUTE)"));
        assertTrue(res1.getAnswer().contains("Đăng ký môn học (ĐKMH)"));

        RagQueryResponse res2 = chatbotService.ask("xin chào", null);
        assertNotNull(res2);
        assertTrue(res2.getAnswer().contains("Tôi sẵn sàng hỗ trợ"));

        RagQueryResponse res3 = chatbotService.ask("bạn là ai", null);
        assertNotNull(res3);
        assertTrue(res3.getAnswer().contains("Trợ lý Cố vấn Học vụ AI"));
    }

    @Test
    @DisplayName("Giai đoạn 5A: Golden Truth trả lời chính xác mã trường SPK")
    void ask_schoolCodeQuestion_returnsGoldenTruthDirectly() {
        RagQueryResponse res = chatbotService.ask("mã trường HCMUTE là gì khi đăng ký nguyện vọng?", null);
        assertNotNull(res);
        assertEquals(1.0, res.getConfidenceScore());
        assertFalse(res.isLlmGenerated());
        assertTrue(res.getAnswer().contains("SPK"), "Phải chứa mã trường SPK");
        assertTrue(res.getAnswer().contains("Nhân bản - Sáng tạo - Hội nhập"));
    }

    @Test
    @DisplayName("Giai đoạn 5A: Golden Truth trả lời chính xác địa chỉ 2 cơ sở và hotline")
    void ask_campusAddressQuestion_returnsGoldenTruthDirectly() {
        RagQueryResponse res = chatbotService.ask("trường có mấy cơ sở và ở đâu?", null);
        assertNotNull(res);
        assertEquals(1.0, res.getConfidenceScore());
        assertTrue(res.getAnswer().contains("02 cơ sở"), "Phải có 02 cơ sở");
        assertTrue(res.getAnswer().contains("Số 1 Võ Văn Ngân"), "Cơ sở 1 Linh Chiểu");
        assertTrue(res.getAnswer().contains("Số 484 Lê Văn Việt"), "Cơ sở 2 Tăng Nhơn Phú A");
        assertTrue(res.getAnswer().contains("3722 5724"), "Phải có hotline tuyển sinh");
    }

    @Test
    @DisplayName("Giai đoạn 5A: Golden Truth trả lời chính xác 11 khoa đào tạo trọng điểm")
    void ask_facultiesListQuestion_returnsGoldenTruthDirectly() {
        RagQueryResponse res = chatbotService.ask("danh sách các khoa của trường HCMUTE", null);
        assertNotNull(res);
        assertTrue(res.getAnswer().contains("Khoa Công nghệ Thông tin (FIT)"));
        assertTrue(res.getAnswer().contains("Khoa Cơ khí Chế tạo máy (FME)"));
        assertTrue(res.getAnswer().contains("Khoa Điện - Điện tử (FEE)"));
        assertTrue(res.getAnswer().contains("Viện Sư phạm Kỹ thuật (ITE)"));
    }

    @Test
    @DisplayName("Giai đoạn 5C: Nối chính xác postId từ PostRepository vào matchedChunks")
    void enrichMatchedChunksWithPostAndDocLinks_enrichesPostIdFromPostRepository() {
        Post fakePost = Post.builder()
                .title("[CÔNG VĂN QUY CHẾ] Quy chế đào tạo đại học chính quy")
                .build();
        fakePost.setId(99L);

        when(postRepository.findFirstByTitleContainingAndIsDeletedFalse("Quy chế đào tạo đại học chính quy"))
                .thenReturn(Optional.of(fakePost));

        List<KnowledgeChunkMatchDto> chunks = new ArrayList<>();
        chunks.add(KnowledgeChunkMatchDto.builder()
                .id(1L)
                .title("Quy chế đào tạo đại học chính quy.pdf")
                .build());

        chatbotService.enrichMatchedChunksWithPostAndDocLinks(chunks);

        assertEquals(99L, chunks.get(0).getPostId(), "Phải enrich đúng postId từ PostRepository");
    }

    @Test
    @DisplayName("Giai đoạn 5B: Khi LLM bận và rơi vào fallback, không dump raw text dài dòng")
    void ask_fallbackDoesNotDumpRawChunk() {
        // Mock hierarchical search
        List<KnowledgeChunkMatchDto> mockChunks = List.of(
                KnowledgeChunkMatchDto.builder()
                        .id(10L)
                        .title("Quy chế đăng ký môn học")
                        .rawContent("Nội dung điều 12 chi tiết sinh viên đăng ký học phần...")
                        .sourceType("REGULATION")
                        .similarityScore(0.85)
                        .build()
        );

        when(ragKnowledgeService.hierarchicalSearch(anyString(), Mockito.any()))
                .thenReturn(RagQueryResponse.builder()
                        .confidenceScore(0.85)
                        .primarySourceType("REGULATION")
                        .matchedChunks(mockChunks)
                        .build());

        // Mock GeminiApiClient returning fallback with 'Máy chủ AI đang tạm bận'
        when(geminiApiClient.generateChatResponseWithFlag(anyString(), anyString()))
                .thenReturn(new String[]{"📋 *Máy chủ AI đang tạm bận, dưới đây là thông tin quy chế liên quan được trích xuất trực tiếp:*\n\n[VĂN BẢN: ...]", "false"});

        RagQueryResponse res = chatbotService.ask("cách đăng ký môn học bổ sung", null);

        assertNotNull(res);
        assertFalse(res.isLlmGenerated());
        assertTrue(res.getAnswer().contains("Hệ thống đã xác định công văn quy chế"),
                "Phải tóm tắt ngắn gọn và trỏ về Huy hiệu Nguồn");
        assertFalse(res.getAnswer().contains("[VĂN BẢN: ...]"), "Tuyệt đối không dump raw chunk tag");
    }

    @Test
    @DisplayName("Hạn chế Chitchat: Lời chào linh hoạt ('alo bot ơi', 'chào ad nhé') phản hồi tức thì")
    void ask_flexibleGreeting_returnsChitchatDirectly() {
        RagQueryResponse res1 = chatbotService.ask("alo bot ơi", null);
        assertNotNull(res1);
        assertTrue(res1.getAnswer().contains("Trợ lý Cố vấn Học vụ AI"));

        RagQueryResponse res2 = chatbotService.ask("chào ad nhé", null);
        assertNotNull(res2);
        assertTrue(res2.getAnswer().contains("Tôi sẵn sàng hỗ trợ"));
    }

    @Test
    @DisplayName("Hạn chế Chitchat: Câu hỏi ngoài lề (Off-topic) bị từ chối lịch sự và định hướng về học vụ")
    void ask_offTopicChitchat_returnsPoliteRefusalAndRedirectsToAcademic() {
        RagQueryResponse res1 = chatbotService.ask("kể chuyện cười đi bot", null);
        assertNotNull(res1);
        assertTrue(res1.getAnswer().contains("Tôi chỉ hỗ trợ giải đáp các vấn đề liên quan đến quy chế học vụ"),
                "Phải từ chối chuyện cười và hướng về học vụ");

        RagQueryResponse res2 = chatbotService.ask("hôm nay ăn gì nhỉ", null);
        assertNotNull(res2);
        assertTrue(res2.getAnswer().contains("Tôi chỉ hỗ trợ"));

        RagQueryResponse res3 = chatbotService.ask("bạn có người yêu chưa", null);
        assertNotNull(res3);
        assertTrue(res3.getAnswer().contains("Tôi chỉ hỗ trợ"));
    }

    @Test
    @DisplayName("Hạn chế Chitchat: Câu hỏi bắt đầu bằng lời chào nhưng có nội dung học vụ thì PHẢI tìm quy chế")
    void ask_greetingWithAcademicQuestion_proceedsToRagSearch() {
        when(ragKnowledgeService.hierarchicalSearch(anyString(), Mockito.any()))
                .thenReturn(RagQueryResponse.builder()
                        .confidenceScore(0.80)
                        .primarySourceType("REGULATION")
                        .matchedChunks(List.of(
                                KnowledgeChunkMatchDto.builder()
                                        .id(1L)
                                        .title("Quy chế đào tạo")
                                        .rawContent("Quy định về đăng ký môn học học kỳ")
                                        .sourceType("REGULATION")
                                        .build()
                        ))
                        .build());
        when(geminiApiClient.generateChatResponseWithFlag(anyString(), anyString()))
                .thenReturn(new String[]{"Sinh viên đăng ký theo thông báo học vụ.", "true"});

        RagQueryResponse res = chatbotService.ask("chào bot, cho em hỏi quy chế đăng ký môn học", null);
        assertNotNull(res);
        // Không được trả lời câu chào chung chung mà phải có kết quả giải đáp
        assertTrue(res.getAnswer().contains("đăng ký") || res.isLlmGenerated());
    }

    @Test
    @DisplayName("Hạn chế Chitchat: Câu hỏi có điểm tương đồng quá thấp (< 0.35) không trích dẫn quy chế bừa bãi")
    void ask_veryLowConfidenceScore_returnsFriendlyNoticeWithoutIrrelevantChunks() {
        when(ragKnowledgeService.hierarchicalSearch(anyString(), Mockito.any()))
                .thenReturn(RagQueryResponse.builder()
                        .confidenceScore(0.20)
                        .primarySourceType("REGULATION")
                        .matchedChunks(List.of(
                                KnowledgeChunkMatchDto.builder().id(1L).title("Công văn không liên quan").build()
                        ))
                        .build());

        RagQueryResponse res = chatbotService.ask("một câu hỏi lạ lẫm không liên quan trường học", null);
        assertNotNull(res);
        assertTrue(res.getAnswer().contains("chưa có thông tin quy định trực tiếp"),
                "Không được trích dẫn bừa bãi khi score quá thấp");
        assertTrue(res.isSuggestCreateTicket());
        assertTrue(res.getMatchedChunks().isEmpty(), "Phải xóa matchedChunks không liên quan");
    }
}
