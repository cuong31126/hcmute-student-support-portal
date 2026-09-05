package com.school.counseling.config;

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

import java.util.Optional;

/**
 * Tự động khởi tạo dữ liệu mẫu (Roles, Departments, Sample Accounts) khi ứng dụng chạy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

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
        } catch (Exception e) {
            log.warn("Lỗi khi khởi tạo dữ liệu mẫu (có thể do đã tồn tại): {}", e.getMessage());
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
}
