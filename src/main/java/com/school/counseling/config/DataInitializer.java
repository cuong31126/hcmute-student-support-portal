package com.school.counseling.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.counseling.module.ai.entity.Faq;
import com.school.counseling.module.ai.repository.FaqRepository;
import com.school.counseling.module.ai.service.SmartFaqMatcherService;
import com.school.counseling.module.auth.entity.Department;
import com.school.counseling.module.auth.entity.Role;
import com.school.counseling.module.auth.entity.User;
import com.school.counseling.module.auth.repository.DepartmentRepository;
import com.school.counseling.module.auth.repository.RoleRepository;
import com.school.counseling.module.auth.repository.UserRepository;
import com.school.counseling.module.feed.entity.Comment;
import com.school.counseling.module.feed.entity.Post;
import com.school.counseling.module.feed.entity.PostAttachment;
import com.school.counseling.module.feed.repository.PostRepository;
import com.school.counseling.module.ai.entity.KnowledgeChunk;
import com.school.counseling.module.ai.repository.KnowledgeChunkRepository;
import com.school.counseling.module.ai.service.GeminiApiClient;
import com.school.counseling.module.ai.service.RagKnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tự động khởi tạo dữ liệu mẫu (Roles, Departments, Sample Accounts, 2.672 FAQs, Feed & Forum Posts) khi ứng dụng chạy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final FaqRepository faqRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final GeminiApiClient geminiApiClient;
    private final RagKnowledgeService ragKnowledgeService;
    private final PostRepository postRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final SmartFaqMatcherService smartFaqMatcherService;

    @Override
    public void run(String... args) {
        try {
            log.info("Bắt đầu kiểm tra và khởi tạo dữ liệu ban đầu...");

            // 1. Khởi tạo Roles
            Role roleStudent = initRole("ROLE_STUDENT", "Sinh viên đại học chính quy");
            Role roleStaff = initRole("ROLE_STAFF", "Cán bộ / Giảng viên tư vấn");
            Role roleAdmin = initRole("ROLE_ADMIN", "Quản trị viên toàn quyền hệ thống");

            // 2. Khởi tạo Departments
            Department deptDoan = initDepartment("Đoàn Thanh niên - Hội Sinh viên", "DOAN_HOI", "A1-102", "doantn@hcmute.edu.vn", "028.3722.1223", "Phong trào sinh viên, tình nguyện, rèn luyện, truyền thông");
            Department deptTuyenSinh = initDepartment("Phòng Tuyển sinh & Truyền thông", "TUYEN_SINH", "A1-101", "tuyensinh@hcmute.edu.vn", "028.3722.5766", "Tư vấn tuyển sinh các hệ, đề án tuyển sinh");
            Department deptDaoTao = initDepartment("Phòng Đào tạo & Công tác Sinh viên", "DAO_TAO", "A1-201", "daotao@hcmute.edu.vn", "028.3896.8641", "Học vụ, điểm số, đăng ký môn học, chứng chỉ ngoại ngữ TOEIC, học bổng");
            Department deptCntt = initDepartment("Khoa Công nghệ Thông tin", "KHOA_CNTT", "E1-402", "cntt@hcmute.edu.vn", "028.3897.2092", "Đồ án tốt nghiệp, học phần chuyên ngành CNTT, thực tập doanh nghiệp");
            Department deptNn = initDepartment("Khoa Ngoại ngữ", "KHOA_NN", "A1-306", "nn@hcmute.edu.vn", "028.3896.1373", "Chuyển điểm chuẩn đầu ra tiếng Anh, kỳ thi ĐGNLTA đầu vào");

            // 3. Khởi tạo Users mẫu (admin: 123456, các tài khoản khác: Password123@)
            String defaultPassword = "Password123@";

            initUser("admin", "123456", "Quản Trị Viên Hệ Thống", "admin@hcmute.edu.vn", roleAdmin, null);
            initUser("staff_tuyensinh", defaultPassword, "ThS. Nguyễn Văn Tuyển Sinh", "tuyensinh.staff@hcmute.edu.vn", roleStaff, deptTuyenSinh);
            initUser("staff_daotao", defaultPassword, "ThS. Phạm Thị Thu Sương", "daotao.staff@hcmute.edu.vn", roleStaff, deptDaoTao);
            initUser("staff_cntt", defaultPassword, "TS. Trần Khoa CNTT", "cntt.staff@hcmute.edu.vn", roleStaff, deptCntt);
            initUser("staff_doan", defaultPassword, "Đ/c Lê Bí Thư Đoàn", "doantn.staff@hcmute.edu.vn", roleStaff, deptDoan);
            initUser("student01", defaultPassword, "Trương Lê Trung Hiếu (SV)", "student01@student.hcmute.edu.vn", roleStudent, null);
            initUser("student02", defaultPassword, "Nguyễn Ngọc Hương Thanh (SV)", "student02@student.hcmute.edu.vn", roleStudent, null);

            log.info("Khởi tạo dữ liệu người dùng mẫu hoàn tất!");

            // 4. Khởi tạo / Đồng bộ Kho Tri Thức 300 FAQs tinh tuyển từ docs/dataset/faq_dataset_curated.json
            // Dọn sạch hoàn toàn các bản ghi rác cũ bị soft-delete (is_deleted = true) trong MySQL
            faqRepository.hardDeleteSoftDeletedFaqs();

            long existingFaqCount = faqRepository.count();
            boolean missingPostDate = (existingFaqCount > 0) && faqRepository.findAll().stream().anyMatch(f -> f.getPostDate() == null);

            if (existingFaqCount != 300 || missingPostDate) {
                log.info("Phát hiện kho FAQ chưa chuẩn hóa (hiện có {} câu, missingPostDate={}). Tiến hành dọn sạch và nạp 300 câu tinh tuyển...",
                        existingFaqCount, missingPostDate);
                faqRepository.hardDeleteAllFaqs();
                faqRepository.resetAutoIncrement();
                seedFaqsFromDataset(deptDoan, deptTuyenSinh, deptDaoTao, deptCntt, deptNn);
            }

            // Đồng bộ KnowledgeChunk (Tầng 2 FAQ_CHAT) nếu chưa có
            if (knowledgeChunkRepository.count() == 0 && faqRepository.count() > 0) {
                syncKnowledgeChunksFromFaqs();
            }

            // 5. Đồng bộ vào bộ nhớ Cache RAM cho Smart FAQ Matcher
            smartFaqMatcherService.reloadCache();

            // 6. Khởi tạo bài viết mẫu cho Bảng Tin & Diễn Đàn Sinh Viên (Sprint 3)
            if (postRepository.count() == 0) {
                seedSamplePosts(deptDaoTao, deptCntt, deptDoan);
            }

        } catch (Exception e) {
            log.warn("Lỗi khi khởi tạo dữ liệu mẫu: {}", e.getMessage(), e);
        }
    }

    private Role initRole(String name, String desc) {
        return roleRepository.findByName(name).orElseGet(() ->
                roleRepository.save(Role.builder().name(name).description(desc).build())
        );
    }

    private Department initDepartment(String name, String code, String location, String email, String phone, String desc) {
        return departmentRepository.findByCode(code).orElseGet(() ->
                departmentRepository.save(Department.builder()
                        .name(name)
                        .code(code)
                        .officeLocation(location)
                        .contactEmail(email)
                        .contactPhone(phone)
                        .description(desc)
                        .isActive(true)
                        .build())
        );
    }

    private void initUser(String username, String rawPassword, String fullName, String email, Role role, Department dept) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            user = User.builder()
                    .username(username)
                    .passwordHash(passwordEncoder.encode(rawPassword))
                    .fullName(fullName)
                    .email(email)
                    .role(role)
                    .department(dept)
                    .status("ACTIVE")
                    .build();
            userRepository.save(user);
            log.info("Tạo tài khoản mẫu: {} (Role: {})", username, role.getName());
        } else {
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            user.setRole(role);
            user.setStatus("ACTIVE");
            userRepository.save(user);
        }
    }

    private void seedFaqsFromDataset(Department deptDoan, Department deptTuyenSinh, Department deptDaoTao, Department deptCntt, Department deptNn) {
        try {
            File file = new File("docs/dataset/faq_dataset_curated.json");
            if (!file.exists()) {
                file = new File("docs/dataset/faq_dataset.json");
            }
            if (!file.exists()) {
                file = new File("form_demo/faq_dataset.json");
            }
            if (!file.exists()) {
                log.info("Không tìm thấy tệp dataset FAQ tại docs/dataset/ hoặc form_demo/, bỏ qua nạp FAQ");
                return;
            }

            log.info("Bắt đầu nạp kho tri thức FAQ từ {}...", file.getPath());
            List<Map<String, Object>> records = objectMapper.readValue(file, new TypeReference<>() {});
            List<Faq> entities = new ArrayList<>();
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

            for (Map<String, Object> r : records) {
                String title = (String) r.get("title");
                String rawQuestion = (String) r.get("question");
                String directAnswer = (String) r.get("answer");
                List<Map<String, String>> replies = (List<Map<String, String>>) r.get("replies");

                String answer = "Vui lòng liên hệ trực tiếp phòng ban phụ trách để được hướng dẫn chi tiết.";
                if (directAnswer != null && !directAnswer.trim().isEmpty()) {
                    answer = directAnswer.trim();
                } else if (replies != null && !replies.isEmpty()) {
                    String firstContent = replies.get(0).get("content");
                    if (firstContent != null && !firstContent.trim().isEmpty()) {
                        answer = firstContent.trim();
                    }
                }

                String cleanQuestion = (title != null && !title.trim().isEmpty()) ? title.trim() : rawQuestion;
                if (cleanQuestion == null || cleanQuestion.isBlank()) {
                    continue;
                }
                if (cleanQuestion.length() > 490) {
                    cleanQuestion = cleanQuestion.substring(0, 490);
                }

                // Parse post_date
                LocalDateTime postDate = null;
                Object postDateObj = r.get("post_date");
                if (postDateObj != null) {
                    String postDateStr = postDateObj.toString().trim();
                    if (!postDateStr.isEmpty()) {
                        try {
                            postDate = LocalDateTime.parse(postDateStr, dtf);
                        } catch (Exception ex) {
                            log.debug("Không parse được post_date '{}': {}", postDateStr, ex.getMessage());
                        }
                    }
                }

                // Phân loại đơn vị theo từ khóa
                Department targetDept = classifyDepartment(cleanQuestion, deptDoan, deptTuyenSinh, deptDaoTao, deptCntt, deptNn);
                String category = resolveCategory(cleanQuestion, targetDept);

                String keywords = cleanQuestion.length() > 250 ? cleanQuestion.substring(0, 250) : cleanQuestion;

                int views = 1;
                try {
                    Object viewsObj = r.get("views");
                    if (viewsObj != null) {
                        views = Integer.parseInt(viewsObj.toString().trim());
                    }
                } catch (Exception ignored) {}

                entities.add(Faq.builder()
                        .question(cleanQuestion)
                        .answer(answer)
                        .department(targetDept)
                        .category(category)
                        .keywords(keywords)
                        .viewCount(views)
                        .postDate(postDate)
                        .isActive(true)
                        .build());
            }

            if (!entities.isEmpty()) {
                faqRepository.saveAll(entities);
                log.info("Đã nạp thành công {} câu hỏi FAQ tinh tuyển vào Database MySQL!", entities.size());
            }
        } catch (Exception e) {
            log.warn("Lỗi khi nạp dữ liệu từ dataset FAQ: {}", e.getMessage(), e);
        }
    }

    private void syncKnowledgeChunksFromFaqs() {
        try {
            List<Faq> faqs = faqRepository.findAll();
            if (faqs.isEmpty()) return;

            log.info("Bắt đầu đồng bộ {} câu FAQ sang KnowledgeChunk (Tầng 2 FAQ_CHAT)...", faqs.size());
            List<KnowledgeChunk> chunks = new ArrayList<>();
            for (Faq faq : faqs) {
                int effectiveYear = faq.getPostDate() != null ? faq.getPostDate().getYear() : 2026;
                String chunkContent = "Câu hỏi: " + faq.getQuestion() + "\n\nGiải đáp: " + faq.getAnswer();
                float[] vector = geminiApiClient.getEmbedding(faq.getQuestion());

                KnowledgeChunk chunk = KnowledgeChunk.builder()
                        .title(faq.getQuestion())
                        .content(chunkContent)
                        .sourceType("FAQ_CHAT")
                        .priorityLevel(2)
                        .effectiveYear(effectiveYear)
                        .department(faq.getDepartment())
                        .isActive(true)
                        .build();
                chunk.setEmbeddingArray(vector);
                chunks.add(chunk);
            }

            knowledgeChunkRepository.saveAll(chunks);
            ragKnowledgeService.reloadVectorCache();
            log.info("Đã nạp thành công {} đoạn tri thức FAQ vào KnowledgeChunk và đồng bộ Cache Vector RAM!", chunks.size());
        } catch (Exception e) {
            log.warn("Lỗi khi đồng bộ FAQ sang KnowledgeChunk: {}", e.getMessage(), e);
        }
    }

    private Department classifyDepartment(String text, Department deptDoan, Department deptTuyenSinh, Department deptDaoTao, Department deptCntt, Department deptNn) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("tuyển sinh") || lower.contains("xét tuyển") || lower.contains("học phí") || lower.contains("chỉ tiêu") || lower.contains("thí sinh") || lower.contains("học bạ")) {
            return deptTuyenSinh;
        }
        if (lower.contains("tiếng anh") || lower.contains("toeic") || lower.contains("vstep") || lower.contains("ielts") || lower.contains("đgnlta") || lower.contains("ngoại ngữ")) {
            return deptNn;
        }
        if (lower.contains("công nghệ thông tin") || lower.contains("cntt") || lower.contains("lập trình") || lower.contains("phần mềm") || lower.contains("đồ án tốt nghiệp cntt")) {
            return deptCntt;
        }
        if (lower.contains("đoàn") || lower.contains("hội sinh viên") || lower.contains("tình nguyện") || lower.contains("mùa hè xanh") || lower.contains("rèn luyện") || lower.contains("kết nạp")) {
            return deptDoan;
        }
        return deptDaoTao; // Mặc định là Phòng Đào tạo & CTSV
    }

    private String resolveCategory(String text, Department dept) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("học phí")) return "Học phí";
        if (lower.contains("tiếng anh") || lower.contains("toeic")) return "Ngoại ngữ";
        if (lower.contains("đăng ký") || lower.contains("môn học")) return "Đăng ký môn học";
        if (lower.contains("tốt nghiệp")) return "Xét tốt nghiệp";
        if (lower.contains("học bổng")) return "Học bổng";
        return "Quy chế học vụ";
    }

    private void seedSamplePosts(Department deptDaoTao, Department deptCntt, Department deptDoan) {
        try {
            User staffDaotao = userRepository.findByUsername("staff_daotao").orElse(null);
            User staffCntt = userRepository.findByUsername("staff_cntt").orElse(null);
            User student01 = userRepository.findByUsername("student01").orElse(null);
            User student02 = userRepository.findByUsername("student02").orElse(null);

            if (staffDaotao != null) {
                Post post1 = Post.builder()
                        .title("Thông báo về việc Đăng ký môn học Học kỳ 1 năm học 2024 - 2025")
                        .content("<p>Phòng Đào tạo thông báo đến toàn thể sinh viên các khóa thời gian và quy định đăng ký môn học trực tuyến cho Học kỳ 1.</p>"
                                + "<p><strong>1. Thời gian đăng ký:</strong> Từ ngày 15/08 đến ngày 25/08 qua cổng đào tạo.</p>"
                                + "<p><strong>2. Lưu ý:</strong> Sinh viên cần kiểm tra kỹ học phần tiên quyết và đảm bảo số tín chỉ tối thiểu theo quy định học vụ.</p>")
                        .postType("OFFICIAL_ANNOUNCEMENT")
                        .department(deptDaoTao)
                        .author(staffDaotao)
                        .status("APPROVED")
                        .isPinned(true)
                        .viewCount(156)
                        .likeCount(24)
                        .videoStatus("NONE")
                        .build();

                post1.addAttachment(PostAttachment.builder()
                        .fileName("Ke-hoach-dang-ky-mon-hoc-HK1-2024-2025.pdf")
                        .fileUrl("/documents/Ke-hoach-dang-ky-mon-hoc-HK1-2024-2025.pdf")
                        .fileType("PDF")
                        .fileSize(2450000L)
                        .build());

                postRepository.save(post1);
            }

            if (staffCntt != null) {
                Post post2 = Post.builder()
                        .title("Kế hoạch Thực tập tốt nghiệp và Đồ án Khóa 2021 - Khoa CNTT")
                        .content("<p>Khoa Công nghệ Thông tin triển khai kế hoạch thực tập doanh nghiệp và đăng ký đề tài tốt nghiệp cho sinh viên khóa 2021.</p>"
                                + "<p>Đề nghị các bạn sinh viên chuẩn bị CV và liên hệ đơn vị thực tập hoặc tham gia ngày hội phỏng vấn tuyển dụng của Khoa.</p>")
                        .postType("OFFICIAL_ANNOUNCEMENT")
                        .department(deptCntt)
                        .author(staffCntt)
                        .status("APPROVED")
                        .isPinned(false)
                        .viewCount(98)
                        .likeCount(15)
                        .videoStatus("COMPLETED")
                        .videoUrl("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
                        .build();

                post2.addAttachment(PostAttachment.builder()
                        .fileName("Bieu-mau-thuc-tap-doanh-nghiep-CNTT.docx")
                        .fileUrl("/documents/Bieu-mau-thuc-tap-doanh-nghiep-CNTT.docx")
                        .fileType("DOCX")
                        .fileSize(512000L)
                        .build());

                postRepository.save(post2);
            }

            if (student01 != null) {
                Post forum1 = Post.builder()
                        .title("Xin kinh nghiệm ôn thi TOEIC chuẩn đầu ra 550+ trong 3 tháng")
                        .content("Chào mọi người, em hiện tại đang là sinh viên năm 3 và cần đạt chuẩn TOEIC 550+ để kịp xét tốt nghiệp. Anh chị và các bạn cho em xin lời khuyên về lộ trình ôn thi và tài liệu luyện nghe hiệu quả với ạ. Cảm ơn mọi người nhiều!")
                        .postType("STUDENT_FORUM")
                        .author(student01)
                        .status("APPROVED")
                        .isPinned(false)
                        .viewCount(42)
                        .likeCount(8)
                        .videoStatus("NONE")
                        .build();

                if (student02 != null) {
                    Comment comment1 = Comment.builder()
                            .author(student02)
                            .content("Bạn nên làm bộ đề ETS 2023 hoặc 2024, mỗi ngày nghe Part 1 & Part 2 tầm 30 phút, tập chép chính tả thì điểm Listening sẽ lên rất nhanh nhé!")
                            .likeCount(3)
                            .build();
                    forum1.addComment(comment1);
                }

                postRepository.save(forum1);
            }

            if (student02 != null) {
                // Bài viết PENDING_APPROVAL để sẵn sàng kiểm thử Moderation Queue
                Post forumPending = Post.builder()
                        .title("Hỏi về quy trình xin cấp lại Thẻ sinh viên tích hợp thẻ xe bus")
                        .content("Em bị rơi mất thẻ sinh viên vào tuần trước, cho em hỏi thời gian làm lại thẻ mất khoảng bao lâu và lệ phí bao nhiêu ạ? Em có cần báo qua phòng CTSV trước không?")
                        .postType("STUDENT_FORUM")
                        .author(student02)
                        .status("PENDING_APPROVAL")
                        .isPinned(false)
                        .viewCount(0)
                        .likeCount(0)
                        .videoStatus("NONE")
                        .build();

                postRepository.save(forumPending);
            }

            log.info("Đã khởi tạo các bài viết mẫu (Bảng tin chính thức & Diễn đàn sinh viên)!");
        } catch (Exception ex) {
            log.warn("Lỗi khi tạo bài viết mẫu: {}", ex.getMessage());
        }
    }
}
