Chuỗi Prompt Chain toàn diện (Từ SRS đến Automated Testing với Playwright)
Bạn có thể copy và thực thi lần lượt từng prompt dưới đây vào AI trợ lý code.

Prompt 1: Đặc tả yêu cầu phần mềm (SRS) & Phân tích nghiệp vụ
Plaintext
Đóng vai trò Solution Architect và Senior Business Analyst. Hãy xây dựng tài liệu đặc tả yêu cầu phần mềm (SRS) chi tiết cho dự án "Hệ thống Web Hỗ trợ và Tư vấn Sinh viên QAUTE" xây dựng trên nền tảng Spring Boot 3 và MySQL/PostgreSQL.

Yêu cầu thực hiện đầy đủ các mục:
1. User Stories theo chuẩn INVEST cho 4 nhóm tác nhân: GUEST, ROLE_STUDENT, ROLE_STAFF (theo Khoa/Phòng), ROLE_ADMIN. Mỗi User Story phải có Acceptance Criteria dạng Gherkin (Given - When - Then).
2. Use Case Specifications dạng bảng chuẩn quốc tế cho các ca sử dụng trọng tâm:
   - UC01: Gửi tin nhắn tư vấn và upload tài liệu minh chứng (PDF/Image).
   - UC02: Chuyển đổi cuộc hội thoại thành Ticket hỗ trợ chính thức và thiết lập SLA/Deadline.
   - UC03: Phân luồng tiếp nhận Ticket theo Khoa/Phòng và xử lý vòng đời Ticket (OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED).
3. Activity Diagram (mô tả dạng text step-by-step) thể hiện luồng liên thông giữa Sinh viên, Bot FAQ tự động, và Cán bộ tư vấn viên.
4. Ma trận phân quyền (RBAC Matrix) bảo vệ truy cập dữ liệu chéo giữa các Khoa.
Prompt 2: Thiết kế Cơ sở dữ liệu & Spring Data JPA Entities
Plaintext
Dựa trên tài liệu SRS từ Prompt 1, hãy đóng vai trò Senior Database Administrator và Java Backend Architect:
1. Viết script SQL DDL chuẩn (tương thích MySQL 8 / PostgreSQL):
   - Bảng: roles, departments, users, conversations, messages, attachments, tickets, ticket_histories, faqs.
   - Thiết lập đầy đủ Primary Key, Foreign Key, Check Constraints (ví dụ: priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')), và Unique index.
   - Đánh Composite Index tối ưu hiệu năng cho: tickets(department_id, status, due_date), messages(conversation_id, created_at).
2. Viết mã nguồn toàn bộ các Class JPA Entity (Java 17+, Spring Boot 3):
   - Sử dụng Lombok (@Getter, @Setter, @NoArgsConstructor, @AllArgsConstructor, @Builder).
   - Cấu hình quan hệ chuẩn: FetchType.LAZY cho toàn bộ @ManyToOne và @OneToMany, tránh lỗi N+1 Query.
   - Đầy đủ Bean Validation annotations (@NotBlank, @NotNull, @Size, @Email).
   - Tạo BaseEntity (@MappedSuperclass) quản lý createdAt, updatedAt, createdBy.
Prompt 3: Cấu hình Spring Security 6, JWT & Phân quyền Khoa
Plaintext
Đóng vai trò Security Engineer, hãy lập trình module bảo mật cho ứng dụng Spring Boot 3 sử dụng Spring Security 6 (không dùng WebSecurityConfigurerAdapter đã deprecated):
1. Cấu hình JWT (JSON Web Token): JwtTokenProvider, JwtAuthenticationFilter, UserPrincipal (implement UserDetails). Hỗ trợ xác thực Stateless.
2. Thiết kế SecurityFilterChain phân tách các vùng URL:
   - /public/**, /auth/**, /faqs/**: Cho phép truy cập tự do (Guest).
   - /student/**: Yêu cầu quyền ROLE_STUDENT.
   - /staff/**: Yêu cầu quyền ROLE_STAFF.
   - /admin/**: Yêu cầu quyền ROLE_ADMIN.
3. Triển khai cơ chế Department-based Authorization (Data Isolating):
   - Viết Custom Security Annotation hoặc Handler Interceptor / SpEL Expression: Đảm bảo Staff của Khoa A không thể truy cập, xem danh sách hoặc tương tác với Ticket/Chat thuộc Khoa B.
   - Đảm bảo Student chỉ đọc và theo dõi được Ticket do chính mình khởi tạo.
Prompt 4: Triển khai Business Service, Repository & REST/Web Controllers
Plaintext
Đóng vai trò Backend Lead Developer, hãy viết toàn bộ tầng Service, Repository và Controller cho 2 module cốt lõi của dự án:
1. Module Chat & Messaging:
   - MessageService: Xử lý lưu tin nhắn, upload file đính kèm (kiểm tra MIME type PDF/PNG/JPG, giới hạn dung lượng < 5MB).
   - Tích hợp WebSocket STOMP Endpoint (/ws-chat) để trao đổi tin nhắn real-time giữa Student và Staff.
2. Module Ticket & Quản lý Deadline (SLA):
   - TicketService hàm convertConversationToTicket(...): Chuyển conversation hiện tại thành Ticket mới, kế thừa toàn bộ lịch sử tin nhắn và file đính kèm; tự động gán due_date dựa vào priority (URGENT: +24h, MEDIUM: +72h, LOW: +7 ngày).
   - TicketRepository: Viết custom query JPQL lấy danh sách ticket Quá hạn (Overdue), Sắp đến hạn trong vòng 24h (Due Soon), và Thống kê tổng hợp theo Khoa.
3. Controller:
   - Trả về cấu trúc response chuẩn ResponseEntity<ApiResponse<T>> kèm mã HTTP status code rõ ràng.
   - Bổ sung GlobalExceptionHandler sử dụng @RestControllerAdvice xử lý triệt để: ResourceNotFoundException, AccessDeniedException, MethodArgumentNotValidException.
Prompt 5: Module Mở rộng (Thymeleaf/Bootstrap UI, Async Mail & FAQ Bot)
Plaintext
Hãy lập trình các module giao diện và mở rộng chức năng:
1. Giao diện (Thymeleaf + Bootstrap 5):
   - Thiết kế giao diện Dashboard cho Staff với hệ thống badge màu phân loại SLA: Đỏ (Quá hạn), Vàng (Sắp đến hạn < 24h), Xanh lục (Bình thường).
   - Bảng hiển thị danh sách Ticket có phân trang, bộ lọc dropdown theo Khoa và Trạng thái.
2. Asynchronous Email Service (@Async + Spring Mail):
   - Cấu hình ThreadPoolTaskExecutor độc lập.
   - Gửi mail thông báo cho Student khi Staff tiếp nhận Ticket hoặc khi có phản hồi mới, nội dung email định dạng HTML chuyên nghiệp kèm link trỏ trực tiếp đến Ticket.
3. Smart FAQ Matcher:
   - Service tự động phân tích từ khóa tin nhắn của sinh viên khi vừa gửi đến. Nếu khớp nội dung trong bảng faqs với độ tin cậy cao, tự động gửi câu trả lời gợi ý trước khi chuyển trạng thái chờ cho Staff.
Prompt 6: Unit & Integration Testing với JUnit 5 & Mockito
Plaintext
Đóng vai trò QA/QC Engineer, hãy viết bộ kiểm thử tự động ở tầng Backend:
1. Unit Tests (JUnit 5 + Mockito):
   - TicketServiceTest: Test case logic chuyển đổi từ Chat sang Ticket; kiểm tra tính toán due_date chính xác theo priority; kiểm tra chặn ngoại lệ khi sai Department.
   - ChatServiceTest: Test case gửi tin nhắn và validate kích thước tệp đính kèm.
2. Integration Tests (@SpringBootTest + @AutoConfigureMockMvc):
   - AuthControllerIT: Kiểm tra luồng đăng nhập, tạo token JWT, chặn đăng nhập khi sai mật khẩu.
   - TicketControllerIT: Kiểm tra quyền truy cập dữ liệu chéo phòng ban; gọi API bằng Token của Staff Khoa A để lấy Ticket của Khoa B và kỳ vọng HTTP 403 Forbidden.
Prompt 7: Automated End-to-End (E2E) Testing với Playwright (Tối ưu nhất)
Plaintext
Đóng vai trò Senior Automation QA Engineer. Hãy thiết lập dự án kiểm thử tự động toàn diện từ đầu đến cuối (End-to-End Testing) cho website bằng Playwright (sử dụng TypeScript hoặc Java Playwright SDK):

1. Cài đặt và Cấu hình Playwright:
   - Cấu hình baseURL, timeout, trace viewer khi test bị fail, và chụp screenshot/video report tự động.
2. Xây dựng cấu trúc Page Object Model (POM):
   - LoginPage.ts: Thao tác nhập user/pass và nhấn nút submit.
   - StudentPortalPage.ts: Thao tác gửi câu hỏi chat, đính kèm file PDF, nhấn nút "Yêu cầu tạo Ticket hỗ trợ".
   - StaffDashboardPage.ts: Thao tác lọc ticket theo Khoa, kiểm tra badge cảnh báo Deadline, nhấn "Nhận xử lý" và gửi phản hồi.
3. Viết kịch bản Test E2E khép kín (End-to-End Scenario):
   - Test case 1: Sinh viên chưa đăng nhập chỉ xem được FAQ và danh bạ, không nhấn gửi ticket được.
   - Test case 2: Sinh viên đăng nhập -> Mở khung chat -> Gõ thắc mắc phúc khảo điểm -> Yêu cầu chuyển thành Ticket -> Kiểm tra ticket hiển thị trạng thái OPEN.
   - Test case 3 (Multi-Context Test): Mở song song 2 trình duyệt riêng biệt (BrowserContext 1 cho Student, BrowserContext 2 cho Staff):
     * Student gửi tin nhắn và đính kèm file qua giao diện web.
     * Kiểm tra màn hình Staff xuất hiện ticket mới theo thời gian thực (WebSocket).
     * Staff bấm "Nhận xử lý" và đổi deadline -> Kiểm tra màn hình Student hiển thị trạng th