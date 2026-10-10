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
import com.school.counseling.module.feed.entity.Post;
import com.school.counseling.module.feed.repository.PostRepository;
import com.school.counseling.module.ai.entity.KnowledgeChunk;
import com.school.counseling.module.ai.repository.KnowledgeChunkRepository;
import com.school.counseling.module.ai.service.GeminiApiClient;
import com.school.counseling.module.ai.service.RagKnowledgeService;
import com.school.counseling.module.ai.service.BatchDocumentIngestionService;
import com.school.counseling.module.ai.repository.KnowledgeDocumentRepository;
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
    private final BatchDocumentIngestionService batchDocumentIngestionService;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;

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
            // ── Tài khoản thực tế của nhóm phát triển (mk: 123456) ──
            initUser("lecuong", "123456", "Lê Quốc Cường (SV)", "lecuong@gmail.com", roleStudent, null);
            initUser("admin_main", "123456", "Admin Hệ Thống (Main)", "admin@gmail.com", roleAdmin, null);
            initUser("lecuong2", "123456", "Lê Cường (Tư Vấn Viên)", "lecuong2@gmail.com", roleStaff, deptDaoTao);

            log.info("Khởi tạo dữ liệu người dùng mẫu hoàn tất!");

            // 4. Khởi tạo / Đồng bộ Kho Tri Thức 300 FAQs tinh tuyển từ docs/dataset/faq_dataset_curated.json
            // Dọn sạch hoàn toàn các bản ghi rác cũ bị soft-delete (is_deleted = true) trong MySQL
            faqRepository.hardDeleteSoftDeletedFaqs();

            long existingFaqCount = faqRepository.count();
            boolean missingPostDate = (existingFaqCount > 0) && faqRepository.findAll().stream().anyMatch(f -> f.getPostDate() == null);

            File datasetFile = new File("docs/dataset/faq_dataset_curated.json");
            long expectedFaqCount = 349;
            if (datasetFile.exists()) {
                try {
                    List<?> recs = objectMapper.readValue(datasetFile, List.class);
                    expectedFaqCount = recs.size();
                } catch (Exception ignored) {}
            }

            if (existingFaqCount == 0 || (existingFaqCount < expectedFaqCount && missingPostDate)) {
                log.info("Kho FAQ chưa khởi tạo hoặc thiếu dữ liệu (hiện có {} câu, kỳ vọng ít nhất {} câu). Tiến hành nạp kho tri thức tinh tuyển...",
                        existingFaqCount, expectedFaqCount);
                if (existingFaqCount == 0) {
                    faqRepository.resetAutoIncrement();
                }
                seedFaqsFromDataset(deptDoan, deptTuyenSinh, deptDaoTao, deptCntt, deptNn);
            }

            // Đồng bộ KnowledgeChunk (Tầng 2 FAQ_CHAT) nếu chưa có
            if (knowledgeChunkRepository.count() == 0 && faqRepository.count() > 0) {
                syncKnowledgeChunksFromFaqs();
            }

            // Đồng bộ Tập Tri Thức Chuẩn Mực (Tầng 0 GOLDEN_TRUTH - Điểm Vàng) vào KnowledgeChunk
            seedGoldenTruthChunks(deptCntt, deptDaoTao);

            // 5. Đồng bộ vào bộ nhớ Cache RAM cho Smart FAQ Matcher
            smartFaqMatcherService.reloadCache();

            // 7. Tự động nạp sẵn toàn bộ kho 91 công văn (D:\HK5\CongNghePhanMem\tailieuAI) và đăng tải lên Bảng tin
            if (batchDocumentIngestionService != null && knowledgeDocumentRepository != null) {
                User adminUser = userRepository.findByUsername("admin").orElse(null);

                // Đăng tải ngay các công văn đã có trong KnowledgeDocument lên Bảng tin (Feed Campaign)
                try {
                    var feedSummary = batchDocumentIngestionService.publishAllDocumentsToFeedCampaign(adminUser);
                    log.info("[DataInitializer] 📢 Đã đồng bộ {} công văn lên Bảng tin chính thức (bỏ qua {} bài trùng lặp)!",
                            feedSummary.getTotalPublished(), feedSummary.getTotalSkipped());
                } catch (Exception ex) {
                    log.warn("[DataInitializer] Không thể đồng bộ công văn lên Bảng tin: {}", ex.getMessage());
                }

                long completedDocCount = knowledgeDocumentRepository.countByStatus("COMPLETED");
                if (completedDocCount < 80) {
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            log.info("[DataInitializer] 🚀 Bắt đầu tự động nạp sẵn kho công văn từ D:\\HK5\\CongNghePhanMem\\tailieuAI (hiện có {}/80+ COMPLETED)...", completedDocCount);
                            var summary = batchDocumentIngestionService.ingestAllYearFolders("D:\\HK5\\CongNghePhanMem\\tailieuAI");
                            log.info("[DataInitializer] ✅ Tự động nạp thành công {} công văn, tạo {} chunks vector!",
                                    summary.getSuccessfulDocuments(), summary.getTotalChunksCreated());

                            // Sau khi nạp thêm văn bản mới, tiếp tục đồng bộ lên Bảng tin
                            batchDocumentIngestionService.publishAllDocumentsToFeedCampaign(adminUser);
                        } catch (Exception ex) {
                            log.warn("[DataInitializer] Lỗi khi tự động nạp kho công văn: {}", ex.getMessage());
                        }
                    });
                }
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
                log.info("Không tìm thấy tệp dataset FAQ tại docs/dataset/faq_dataset_curated.json, bỏ qua nạp FAQ");
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
                float[] vector = null;
                try {
                    vector = geminiApiClient.getEmbedding(faq.getQuestion());
                } catch (Exception e) {
                    vector = generateDeterministicEmbedding(faq.getQuestion());
                }
                if (vector == null || vector.length == 0) {
                    vector = generateDeterministicEmbedding(faq.getQuestion());
                }

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

    private float[] generateDeterministicEmbedding(String text) {
        return GeminiApiClient.generateDeterministicVector(text);
    }

    private void seedGoldenTruthChunks(Department deptCntt, Department deptDaoTao) {
        try {
            long existing = knowledgeChunkRepository.countBySourceType("GOLDEN_TRUTH");
            if (existing > 0) {
                log.info("[GoldenTruth] Đã có sẵn {} bản ghi Chân lý Golden Truth trong MySQL.", existing);
                return;
            }

            File file = new File("golden-truth.txt");
            if (!file.exists()) {
                file = new File("src/main/resources/golden-truth.txt");
            }

            List<KnowledgeChunk> chunks = new ArrayList<>();
            if (file.exists()) {
                String content = java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                String[] lines = content.split("\\r?\\n");

                String currentQ = null;
                StringBuilder currentA = new StringBuilder();

                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("#### Q:")) {
                        if (currentQ != null && currentA.length() > 0) {
                            chunks.add(buildGoldenChunk(currentQ, currentA.toString().trim(), deptCntt, deptDaoTao));
                            currentA.setLength(0);
                        }
                        currentQ = trimmed.substring(7).trim();
                    } else if (currentQ != null) {
                        if (trimmed.startsWith("#### ") && !trimmed.startsWith("#### Q:")) {
                            chunks.add(buildGoldenChunk(currentQ, currentA.toString().trim(), deptCntt, deptDaoTao));
                            currentQ = null;
                            currentA.setLength(0);
                        } else {
                            currentA.append(line).append("\n");
                        }
                    }
                }
                if (currentQ != null && currentA.length() > 0) {
                    chunks.add(buildGoldenChunk(currentQ, currentA.toString().trim(), deptCntt, deptDaoTao));
                }
            }

            // Đảm bảo luôn có các thông tin nhân sự và cơ cấu tối quan trọng
            addEssentialGoldenChunks(chunks, deptCntt, deptDaoTao);

            if (!chunks.isEmpty()) {
                knowledgeChunkRepository.saveAll(chunks);
                ragKnowledgeService.reloadVectorCache();
                log.info("[GoldenTruth] ✅ Đã nạp thành công {} đoạn tri thức Chuẩn mực (GOLDEN_TRUTH - Điểm Vàng) vào MySQL và RAM!", chunks.size());
            }
        } catch (Exception e) {
            log.warn("[GoldenTruth] Lỗi khi nạp Golden Truth: {}", e.getMessage(), e);
        }
    }

    private KnowledgeChunk buildGoldenChunk(String q, String a, Department deptCntt, Department deptDaoTao) {
        String answerClean = a.startsWith("**A:**") ? a.substring(6).trim() : a.trim();
        String fullContent = "Câu hỏi: " + q + "\n\nTrả lời chuẩn mực (Golden Truth): " + answerClean;
        float[] vector = GeminiApiClient.generateDeterministicVector(q + " " + answerClean);

        String qLower = q.toLowerCase();
        Department dept = (qLower.contains("công nghệ thông tin") || qLower.contains("cntt") || qLower.contains("fit"))
                ? deptCntt : deptDaoTao;

        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .title(q)
                .content(fullContent)
                .articleHeader("Tập Chân Lý Học Vụ (Golden Truth)")
                .sourceType("GOLDEN_TRUTH")
                .priorityLevel(1)
                .effectiveYear(2026)
                .department(dept)
                .isActive(true)
                .build();
        chunk.setEmbeddingArray(vector);
        return chunk;
    }

    private void addEssentialGoldenChunks(List<KnowledgeChunk> chunks, Department deptCntt, Department deptDaoTao) {
        boolean hasItDean = chunks.stream().anyMatch(c -> c.getTitle() != null && c.getTitle().toLowerCase().contains("trưởng khoa công nghệ thông tin"));
        if (!hasItDean) {
            chunks.add(buildGoldenChunk(
                    "Ai đang giữ chức vụ Trưởng khoa Công nghệ Thông tin (FIT / IT) và thông tin liên hệ là gì?",
                    "Trưởng khoa Công nghệ Thông tin hiện nay là GVC. TS. Lê Văn Vinh (đồng thời là giảng viên thuộc Bộ môn Công nghệ Phần mềm). Email liên hệ: vinhlv@hcmute.edu.vn.",
                    deptCntt, deptDaoTao
            ));
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


}
