package com.school.counseling.module.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfExtractorUtilsTest {

    @Test
    @DisplayName("TDD-PDF-01: Bóc tách mã số hiệu công văn chính xác từ chuỗi tiêu đề")
    void extractDocumentCode_validInput_returnsCorrectCode() {
        String sampleText = "TRƯỜNG ĐẠI HỌC SƯ PHẠM KỸ THUẬT TP.HCM\n" +
                "Số: 1084/QĐ-ĐHSPKT\n" +
                "QUYẾT ĐỊNH\n" +
                "Về việc ban hành Quy chế khen thưởng sinh viên năm học 2025-2026";

        String code = PdfExtractorUtils.extractDocumentCode(sampleText);
        assertEquals("1084/QĐ-ĐHSPKT", code);
    }

    @Test
    @DisplayName("TDD-PDF-02: Nhận diện tự động danh mục nghiệp vụ chính xác")
    void detectCategory_variousContents_returnsAccurateCategory() {
        assertEquals("HOC_PHI", PdfExtractorUtils.detectCategory("Thông báo thu học phí kỳ 2", "Mức thu học phí theo tín chỉ"));
        assertEquals("TOT_NGHIEP", PdfExtractorUtils.detectCategory("Kế hoạch bảo vệ Đồ án tốt nghiệp", "Hạn chót nộp khóa luận tốt nghiệp"));
        assertEquals("HOC_BONG", PdfExtractorUtils.detectCategory("Xét học bổng khuyến khích", "Hỗ trợ sinh viên có hoàn cảnh khó khăn"));
        assertEquals("NGOAI_NGU", PdfExtractorUtils.detectCategory("Chuẩn tiếng Anh đầu ra", "Chứng chỉ Cambridge B1 hoặc TOEIC 500"));
        assertEquals("LICH_THI", PdfExtractorUtils.detectCategory("Thông báo lịch thi học kỳ 2", "Phòng thi và thời gian thi"));
    }

    @Test
    @DisplayName("TDD-PDF-03: Structural Chunking tiêm đúng Header và nhận diện Điều/Khoản")
    void chunkStructural_validPages_injectsHeaderAndDetectsArticle() {
        String pageText = "Điều 5. Mức thu học phí các ngành đào tạo\n\n" +
                "1. Đối với chương trình chuẩn: 850.000 VNĐ / tín chỉ lý thuyết.\n" +
                "2. Đối với chương trình chất lượng cao: 1.250.000 VNĐ / tín chỉ.\n" +
                "3. Thời hạn nộp học phí trước ngày 15/10/2026.";

        List<PdfExtractorUtils.PageContent> pages = List.of(
                new PdfExtractorUtils.PageContent(1, pageText)
        );

        List<PdfExtractorUtils.StructuralChunk> chunks = PdfExtractorUtils.chunkStructural(
                pages,
                "Quy định học phí 2026",
                "3005/QĐ-ĐHSPKT",
                2026,
                "Phòng Kế hoạch Tài chính"
        );

        assertFalse(chunks.isEmpty());
        PdfExtractorUtils.StructuralChunk chunk = chunks.get(0);

        assertEquals(1, chunk.pageNumber());
        assertTrue(chunk.articleHeader().contains("Điều 5"));
        assertTrue(chunk.injectedContent().contains("[VĂN BẢN: Quy định học phí 2026"));
        assertTrue(chunk.injectedContent().contains("SỐ HIỆU: 3005/QĐ-ĐHSPKT"));
        assertTrue(chunk.injectedContent().contains("NĂM BAN HÀNH: 2026"));
        assertTrue(chunk.injectedContent().contains("TRANG: 1"));
        assertTrue(chunk.injectedContent().contains("ĐIỀU/MỤC: Điều 5. Mức thu học phí các ngành đào tạo"));
        assertTrue(chunk.injectedContent().contains("[NỘI DUNG]:"));
    }
}
