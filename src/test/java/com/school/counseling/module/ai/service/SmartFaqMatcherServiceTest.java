package com.school.counseling.module.ai.service;

import com.school.counseling.module.ai.entity.Faq;
import com.school.counseling.module.ai.repository.FaqRepository;
import com.school.counseling.module.auth.entity.Department;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartFaqMatcherServiceTest {

    @Mock
    private FaqRepository faqRepository;

    @InjectMocks
    private SmartFaqMatcherService faqMatcherService;

    private Department deptDaoTao;
    private Department deptTuyenSinh;

    @BeforeEach
    void setUp() {
        deptDaoTao = Department.builder().id(1L).name("Phòng Đào tạo").code("DT").build();
        deptTuyenSinh = Department.builder().id(2L).name("Phòng Tuyển sinh").code("TS").build();

        List<Faq> sampleFaqs = List.of(
                Faq.builder()
                        .id(1L)
                        .question("Thời hạn nộp học phí học kỳ 1 năm 2026 khi nào?")
                        .answer("Hạn chót nộp học phí là ngày 30/10/2026 qua cổng thanh toán.")
                        .category("Học phí")
                        .keywords("học phí, học kỳ 1, thanh toán")
                        .department(deptDaoTao)
                        .viewCount(100)
                        .isActive(true)
                        .build(),
                Faq.builder()
                        .id(2L)
                        .question("Quy định chuẩn đầu ra ngoại ngữ TOEIC đối với sinh viên khóa 2026")
                        .answer("Sinh viên đại trà cần đạt tối thiểu TOEIC 500 điểm trước khi xét tốt nghiệp.")
                        .category("Quy chế")
                        .keywords("toeic, ngoại ngữ, chuẩn đầu ra, tiếng anh")
                        .department(deptDaoTao)
                        .viewCount(250)
                        .isActive(true)
                        .build(),
                Faq.builder()
                        .id(3L)
                        .question("Hồ sơ đăng ký xét tuyển bổ sung đại học chính quy")
                        .answer("Thí sinh nộp phiếu điểm thi THPT và học bạ tại phòng Tuyển sinh.")
                        .category("Tuyển sinh")
                        .keywords("tuyển sinh, xét tuyển, điểm thi")
                        .department(deptTuyenSinh)
                        .viewCount(80)
                        .isActive(true)
                        .build()
        );

        when(faqRepository.findAllActiveWithDepartment()).thenReturn(sampleFaqs);
        faqMatcherService.reloadCache();
    }

    @Test
    @DisplayName("TC-01: Chuẩn hóa tiếng Việt bỏ dấu thành công")
    void testNormalize_VietnameseAccents() {
        assertEquals("hoc phi", faqMatcherService.normalize("Học phí"));
        assertEquals("dong hoc phi ky 1", faqMatcherService.normalize("Đóng học phí kỳ 1"));
        assertEquals("chuan dau ra toeic", faqMatcherService.normalize("Chuẩn đầu ra TOEIC"));
    }

    @Test
    @DisplayName("TC-02: Tìm kiếm từ khóa 'học phí' ra kết quả chuẩn xác")
    void testMatchQuestion_HocPhi() {
        List<SmartFaqMatcherService.FaqMatchResult> results = faqMatcherService.matchQuestion("học phí", null);

        assertFalse(results.isEmpty(), "Kết quả tìm kiếm không được rỗng khi từ khóa có trong câu hỏi");
        assertEquals(1L, results.get(0).id());
        assertTrue(results.get(0).question().contains("học phí"));
        assertEquals("Phòng Đào tạo", results.get(0).departmentName());
    }

    @Test
    @DisplayName("TC-03: Tìm kiếm không dấu 'hoc phi' vẫn ra câu hỏi có dấu")
    void testMatchQuestion_NoAccents() {
        List<SmartFaqMatcherService.FaqMatchResult> results = faqMatcherService.matchQuestion("hoc phi", null);

        assertFalse(results.isEmpty());
        assertEquals(1L, results.get(0).id());
    }

    @Test
    @DisplayName("TC-04: Tìm kiếm 'toeic' ra câu hỏi chuẩn đầu ra ngoại ngữ")
    void testMatchQuestion_Toeic() {
        List<SmartFaqMatcherService.FaqMatchResult> results = faqMatcherService.matchQuestion("toeic", null);

        assertFalse(results.isEmpty());
        assertEquals(2L, results.get(0).id());
    }

    @Test
    @DisplayName("TC-05: Lọc theo Đơn vị Phòng Tuyển sinh")
    void testMatchQuestion_FilterByDepartment() {
        List<SmartFaqMatcherService.FaqMatchResult> results = faqMatcherService.matchQuestion("xét tuyển", 2L);

        assertFalse(results.isEmpty());
        assertEquals(3L, results.get(0).id());
        assertEquals("Phòng Tuyển sinh", results.get(0).departmentName());
    }

    @Test
    @DisplayName("TC-06: Truy vấn rỗng hoặc từ khóa không tồn tại")
    void testMatchQuestion_EmptyOrNotFound() {
        assertTrue(faqMatcherService.matchQuestion("", null).isEmpty());
        assertTrue(faqMatcherService.matchQuestion("   ", null).isEmpty());
        assertTrue(faqMatcherService.matchQuestion("từkhóakhônghềtồntại123456", null).isEmpty());
    }
}
