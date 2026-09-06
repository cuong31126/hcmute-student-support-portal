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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tự động khởi tạo dữ liệu mẫu (Roles, Departments, Sample Accounts, 2.672 FAQs) khi ứng dụng chạy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final FaqRepository faqRepository;
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

            // 3. Khởi tạo Users mẫu với mật khẩu "Password123@"
            String defaultPassword = "Password123@";

            initUser("admin", defaultPassword, "Quản Trị Viên Hệ Thống", "admin@hcmute.edu.vn", roleAdmin, null);
            initUser("staff_tuyensinh", defaultPassword, "ThS. Nguyễn Văn Tuyển Sinh", "tuyensinh.staff@hcmute.edu.vn", roleStaff, deptTuyenSinh);
            initUser("staff_daotao", defaultPassword, "ThS. Phạm Thị Thu Sương", "daotao.staff@hcmute.edu.vn", roleStaff, deptDaoTao);
            initUser("staff_cntt", defaultPassword, "TS. Trần Khoa CNTT", "cntt.staff@hcmute.edu.vn", roleStaff, deptCntt);
            initUser("staff_doan", defaultPassword, "Đ/c Lê Bí Thư Đoàn", "doantn.staff@hcmute.edu.vn", roleStaff, deptDoan);
            initUser("student01", defaultPassword, "Trương Lê Trung Hiếu (SV)", "student01@student.hcmute.edu.vn", roleStudent, null);
            initUser("student02", defaultPassword, "Nguyễn Ngọc Hương Thanh (SV)", "student02@student.hcmute.edu.vn", roleStudent, null);

            log.info("Khởi tạo dữ liệu người dùng mẫu hoàn tất!");

            // 4. Khởi tạo Kho Tri Thức 2.672 FAQs thực tế từ form_demo/faq_dataset.json
            if (faqRepository.count() == 0) {
                seedFaqsFromDataset(deptDoan, deptTuyenSinh, deptDaoTao, deptCntt, deptNn);
            }

            // 5. Đồng bộ vào bộ nhớ Cache RAM cho Smart FAQ Matcher
            smartFaqMatcherService.reloadCache();

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
        if (!userRepository.existsByUsername(username)) {
            User user = User.builder()
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
        }
    }

    private void seedFaqsFromDataset(Department deptDoan, Department deptTuyenSinh, Department deptDaoTao, Department deptCntt, Department deptNn) {
        try {
            File file = new File("form_demo/faq_dataset.json");
            if (!file.exists()) {
                log.info("Không tìm thấy tệp form_demo/faq_dataset.json, bỏ qua nạp FAQ");
                return;
            }

            log.info("Bắt đầu nạp kho tri thức FAQ từ form_demo/faq_dataset.json...");
            List<Map<String, Object>> records = objectMapper.readValue(file, new TypeReference<>() {});
            List<Faq> entities = new ArrayList<>();

            for (Map<String, Object> r : records) {
                String title = (String) r.get("title");
                String rawQuestion = (String) r.get("question");
                List<Map<String, String>> replies = (List<Map<String, String>>) r.get("replies");

                String answer = "Vui lòng liên hệ trực tiếp phòng ban phụ trách để được hướng dẫn chi tiết.";
                if (replies != null && !replies.isEmpty()) {
                    String firstContent = replies.get(0).get("content");
                    if (firstContent != null && !firstContent.trim().isEmpty()) {
                        answer = firstContent.trim();
                    }
                }

                if (title != null && !title.trim().isEmpty()) {
                    String cleanQuestion = title.trim();
                    if (cleanQuestion.length() > 490) {
                        cleanQuestion = cleanQuestion.substring(0, 490);
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
                            .isActive(true)
                            .build());
                }
            }

            if (!entities.isEmpty()) {
                faqRepository.saveAll(entities);
                log.info("Đã nạp thành công {} câu hỏi FAQ thực tế vào Database MySQL!", entities.size());
            }
        } catch (Exception e) {
            log.warn("Lỗi khi nạp dữ liệu từ faq_dataset.json: {}", e.getMessage(), e);
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
