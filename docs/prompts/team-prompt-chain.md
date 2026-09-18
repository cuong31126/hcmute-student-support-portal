# PHẦN 1: MA TRẬN PHÂN QUYỀN VÀ NGHIỆP VỤ HỆ THỐNG
Hệ thống phân tách rạch ròi giữa kênh Hội thoại/Ticket kín và kênh Cộng đồng/Bảng tin công khai:
┌─────────────────────────────────────────────────────────────────────────────┐
│                           BẢNG TIN & DIỄN ĐÀN                               │
├─────────────────────────────────────────────────────────────────────────────┤
│  [STAFF / ADMIN] (Khoa, Phòng Tuyển sinh, Đoàn Thanh niên, P. Đào tạo)      │
│    ├── Đăng bài viết chính thức (Thông báo, Thông tư, Quy chế, Tuyển sinh)  │
│    ├── Đính kèm không giới hạn: PDF, Word, Excel, Hình ảnh, Video MP4       │
│    └── Tích hợp Webhook nhận Video tự động từ Microservice Node.js           │
│                                                                             │
│  [STUDENT / USER]                                                           │
│    ├── Đăng bài ở "Diễn đàn sinh viên" (Trạng thái: Chờ duyệt)              │
│    ├── Định dạng kiểu Facebook: Text status + Thảo luận trao đổi            │
│    ├── Bình luận (Comment), Thả cảm xúc (Like), Báo cáo vi phạm (Report)    │
│    └── Tra cứu và tương tác với Trợ lý AI RAG (Đọc tài liệu PDF & DB)       │
└─────────────────────────────────────────────────────────────────────────────┘

---

# PHẦN 2: CHUỖI 5 PROMPT CHAIN TOÀN DIỆN CHO DỰ ÁN SPRING BOOT 3

## 🟢 PROMPT 1: Khởi tạo Product Brief & Business Rules (`docs/brief.md`)
**Mục tiêu:** Định hình rõ phạm vi MVP, sơ đồ trạng thái, quyền hạn các phòng ban (Tuyển sinh, Đoàn trường, Đào tạo, Khoa), tích hợp Microservice Node.js và AI RAG.

```text
Bạn là Technical Product Manager và Senior Software Architect. 
Tôi đang xây dựng đồ án Web cuối kỳ bằng Java Spring Boot 3, MySQL, Thymeleaf và Bootstrap 5.

[THÔNG TIN DỰ ÁN]:
- Tên hệ thống: QAUTE Portal - Hệ thống Tư vấn Sinh viên, Quản lý Ticket SLA, Bảng tin Đa phương tiện tích hợp AI RAG & Video Node.js.
- Các Đơn vị / Phòng ban trọng tâm (Departments):
  + Đoàn Thanh niên & Hội Sinh viên (Hoạt động phong trào, tình nguyện, video truyền thông).
  + Phòng Tuyển sinh & Truyền thông (Tư vấn tuyển sinh, đề án tuyển sinh, video giới thiệu ngành).
  + Phòng Đào tạo & Công tác Sinh viên (Học vụ, điểm số, học bổng, kỷ luật).
  + Các Khoa chuyên môn (Khoa CNTT, Ngoại ngữ, Kinh tế...).
- Đối tượng sử dụng:
  + GUEST: Khách vãng lai, thí sinh (hỏi đáp tuyển sinh với Chatbot AI RAG, tra cứu FAQ, xem bài viết công khai, gửi ticket tư vấn).
  + ROLE_STUDENT: Sinh viên (chat tư vấn, gửi Ticket trực tiếp, đăng bài thảo luận chờ duyệt, comment, like, hỏi AI).
  + ROLE_STAFF: Cán bộ Khoa/Phòng/Đoàn/Tuyển sinh (tiếp nhận Ticket theo Khoa, gắn Deadline/SLA, đăng bài viết chính thức kèm file PDF, Excel, Word, Video MP4).
  + ROLE_ADMIN: Quản trị viên (quản trị người dùng, duyệt bài thảo luận sinh viên, xử lý báo cáo vi phạm, thống kê).
- Hệ thống vệ tinh liên kết:
  + Microservice Node.js: Tự động render/edit video truyền thông và bắn Webhook sang Spring Boot để đính kèm vào bài viết.
  + AI RAG Engine: Trích xuất tri thức từ toàn bộ tài liệu PDF/Docx của các Phòng ban và Database để trả lời tự động cho User/Guest.

HÃY SOẠN THẢO NỘI DUNG FILE docs/brief.md ĐẦY ĐỦ CÁC MỤC SAU:
1. Executive Summary & Core Objectives (Mục tiêu cốt lõi: Tự động hóa tư vấn bằng AI RAG, phân luồng Ticket chuẩn SLA và xây dựng Cổng thông tin đa phương tiện).
2. Product Scope:
   - Module 1: Kênh tư vấn Ticket & Deadline (SLA) phân luồng theo Khoa/Phòng/Đoàn thể (Hỗ trợ tạo form trực tiếp và chuyển từ Chat).
   - Module 2: Bảng tin chính thức (Staff đăng thông báo kèm tài liệu PDF, Excel, Video MP4).
   - Module 3: Diễn đàn thảo luận sinh viên (Feed dạng Facebook: đăng status chờ duyệt, comment, like, báo cáo vi phạm).
   - Module 4: Tích hợp Microservice Node.js (Webhook nhận Video MP4 render tự động).
   - Module 5: Trợ lý AI RAG & Chatbot Thông minh (Đọc PDF/DB trả lời tự động, fallback sang tạo Ticket).
3. Role-Based Access Control (RBAC) Matrix: Bảng phân quyền chi tiết cho Guest, Student, Staff, Admin trên từng module.
4. Business Rules List (Bắt buộc đánh mã định danh duy nhất):
   - BRULE-AUTH-001 -> 005: OTP kích hoạt email, mã hóa BCrypt, cơ chế bảo mật Session Cookie (HttpOnly, SameSite=Lax, Spring CSRF), phân quyền theo Role và Khoa/Phòng.
   - BRULE-TICKET-001 -> 007:
     + Hỗ trợ 2 luồng tạo Ticket: (1) Tạo trực tiếp qua Form gửi yêu cầu (cho cả Student và Guest nhập Email), (2) Chuyển đổi trực tiếp từ phiên Chat tư vấn.
     + Tính toán due_date tự động theo priority (URGENT: +24h, MEDIUM: +72h, LOW: +7 ngày).
     + Quy tắc Claim ticket, cấm can thiệp chéo giữa các Khoa/Phòng.
     + Async Email Service: Gửi mail tự động cập nhật tiến độ (Đã nhận xử lý, Có phản hồi mới, Đã giải quyết) đến Email của Sinh viên hoặc Guest.
   - BRULE-POST-001: Staff/Admin đăng bài viết chính thức (Thông báo, Quy chế) kèm tệp: .pdf, .docx, .xlsx, .mp4 dung lượng tối đa 100MB (hiển thị ngay lập tức).
   - BRULE-POST-002: Student đăng bài thảo luận (văn bản thuần + tối đa 1 ảnh mô tả). Trạng thái ban đầu là PENDING_APPROVAL.
   - BRULE-POST-003 (Kiểm duyệt bài viết): Bài thảo luận của Sinh viên BẮT BUỘC phải được Staff hoặc Admin phê duyệt (Chuyển sang APPROVED) mới được hiển thị công khai trên Diễn đàn.
   - BRULE-POST-004 (Báo cáo vi phạm - Report): Người dùng có thể nhấn "Báo cáo bài viết" (chọn lý do: vi phạm đạo đức, ngôn từ xúc phạm, sai lệch học vụ). Hệ thống ghi nhận vào bảng post_reports và gửi cảnh báo đến trang Kiểm duyệt của Staff/Admin để xử lý (Ẩn bài / Khóa người dùng).
   - BRULE-NODE-001 (Node.js Video Webhook): Endpoint bảo mật bằng API Key/Signature nhận thông báo khi Node.js render xong video, tự động gán URL video vào bài viết của Đoàn trường / Tuyển sinh.
   - BRULE-RAG-001 (AI RAG Assistant): Khi User/Guest đặt câu hỏi tại khung Chat, AI tự động tìm kiếm ngữ nghĩa trong Vector Store (tri thức từ PDF văn bản học vụ, thông báo tuyển sinh, FAQ) để trả lời trích dẫn nguồn. Nếu câu hỏi vượt quá phạm vi, AI chủ động gợi ý "Bạn có muốn gửi Ticket cho Cán bộ phụ trách không?".
5. Milestone Roadmap: Lộ trình 5 Sprint (Sprint 1: Core & Schema; Sprint 2: Ticket & SLA & Guest Mail; Sprint 3: Bảng tin, Duyệt bài, Báo cáo vi phạm & Webhook Video; Sprint 4: AI RAG & Notifications; Sprint 5: E2E Testing với Playwright).

Lưu ý: Tập trung hoàn toàn vào chuẩn hóa logic nghiệp vụ, quy chuẩn ràng buộc, không viết code Java ở bước này.
```

---

## 🟢 PROMPT 2: Quy chuẩn Kỹ thuật, Giao diện Tối giản & Clean Architecture (`docs/team/engineering-rules.md`)
**Mục tiêu:** Đóng khung cấu trúc Spring Boot 3, JPA Entity, Thymeleaf layout chuẩn tối giản, Cloudinary Storage, AI RAG Client và quản lý kết nối MySQL.

```text
Dựa trên docs/brief.md, hãy đóng vai Lead Software Architect để viết file docs/team/engineering-rules.md quy định tiêu chuẩn kỹ thuật bắt buộc cho dự án Spring Boot 3 + MySQL:

TIÊU CHUẨN CẦN QUY ĐỊNH CHI TIẾT:
1. Thứ tự ưu tiên văn bản (Hierarchy of Truth):
   docs/brief.md > docs/team/engineering-rules.md > dev-assignment.md > dev-tasks.md.

2. Cấu trúc thư mục Modular Monolith:
   com.school.counseling
     ├── config/ (Security, WebSocket, Async, CloudinaryStorageConfig, AiConfig)
     ├── common/ (BaseEntity, ApiResponse, GlobalExceptionHandler)
     └── module/
           ├── auth/ (User, Role, SecurityFilterChain với Session Cookie)
           ├── ticket/ (Ticket, Message, Department, SLA Engine, GuestTicket)
           ├── feed/ (Post, PostReport, Comment, Reaction, Attachment, ModerationService)
           ├── integration/ (NodejsWebhookController, VideoProcessingService)
           ├── ai/ (RagService, DocumentEmbeddingService, AiChatController)
           └── notification/ (EmailService, NotificationLog)
   Quy tắc: Không chia package dạng phẳng (không gom chung tất cả controller vào một chỗ).

3. Tiêu chuẩn kiến trúc Layered Architecture:
   - Controller: Chỉ điều hướng, validate @Valid DTO, trả về View name (Thymeleaf) hoặc ResponseEntity.
   - Service: Chứa 100% Business logic; mọi thay đổi trạng thái phải bọc trong @Transactional.
   - Repository: Kế thừa JpaRepository, viết JPQL chuẩn; cấm Native Query trừ trường hợp thống kê phức tạp.
   - Entity: Dùng Java 17 class kèm Lombok, FetchType.LAZY cho toàn bộ @ManyToOne/@OneToMany; cấu hình Soft Delete bằng @SQLDelete và @Where.
   - View (Thymeleaf): Cấu hình Layout Dialect hoặc th:replace chuẩn; validation hiển thị qua th:errors.

4. Tiêu chuẩn Thiết kế Giao diện (UI/UX - BẮT BUỘC TỐI GIẢN & SẠCH SẼ):
   - Phong cách: Thiết kế tối giản, thanh lịch chuẩn cổng thông tin trường học/công sở (Clean & Minimal Academic Style).
   - Màu sắc: Tông màu trung tính, dịu mắt (Nền trắng/xám sáng #f8f9fa, Navbar xanh navy hoặc xám slate, chữ màu xám đậm #333). TUYỆT ĐỐI KHÔNG dùng màu mè sặc sỡ, không dùng gradient chói lóa.
   - CSS Framework: Tận dụng 100% Bootstrap 5 nguyên bản (utility classes chuẩn). Hạn chế tối đa viết custom CSS rườm rà.
   - Bố cục: Thẻ Card, Table, Form có spacing rộng rãi, dễ nhìn; Badge trạng thái dùng class chuẩn của Bootstrap (bg-primary, bg-success, bg-warning, bg-danger).

5. Quy chuẩn Upload & Quản lý File (Cloudinary + Local Storage):
   - Xây dựng IStorageService hỗ trợ 2 implementation: CloudinaryStorageServiceImpl (lưu trữ cloud) và LocalStorageServiceImpl (lưu trữ ổ cứng dự phòng).
   - Phân tách thư mục: /documents (pdf, docx, xlsx), /videos (mp4), /images (png, jpg).
   - Kiểm tra MIME Type thực tế từ Content-Type và Magic Number, không dựa vào đuôi file mở rộng.

6. Xử lý Flash Message & Lỗi:
   - Dùng RedirectAttributes addFlashAttribute("successMessage", ...) hoặc ("errorMessage", ...).
   - Trang lỗi tập trung: templates/error/403.html, 404.html, 500.html.
```

---

## 🟢 PROMPT 3: Phân chia Ownership & Khóa Hợp đồng Code (`dev-a-assignment.md` & `dev-b-assignment.md`)
**Mục tiêu:** Khóa ranh giới thư mục, tạo DDL MySQL và định nghĩa sẵn các Locked Interface để tránh xung đột code.

```text
Dựa trên docs/brief.md và docs/team/engineering-rules.md, hãy phân bổ toàn bộ hệ thống cho 2 lập trình viên chính:
- Dev A: Phụ trách Core Security, Module Ticket & SLA (Hỗ trợ 2 luồng tạo Ticket + Async Mail Guest/Student), Phân luồng Khoa/Phòng/Đoàn thể, và Module AI RAG Chatbot.
- Dev B: Phụ trách Bảng tin đa phương tiện (Đăng tin tuyển sinh/đoàn trường kèm file/video), Luồng Duyệt bài, Báo cáo vi phạm, Comment, Storage và Webhook tiếp nhận Video từ Node.js.

HÃY SOẠN THẢO 2 FILE docs/team/dev-a-assignment.md VÀ docs/team/dev-b-assignment.md VỚI CÁC MỤC:

1. Thư mục & File Ownership (Quyền sở hữu tuyệt đối):
   - Liệt kê chính xác file/package của Dev A (module/auth, module/ticket, module/ai, templates/ticket/...).
   - Liệt kê chính xác file/package của Dev B (module/feed, module/integration, config/storage, templates/feed/...).
   - Nguyên tắc: Không được phép commit vào package của người khác.

2. Database Schema DDL (MySQL 8):
   - Viết trọn bộ câu lệnh SQL DDL chuẩn (users, roles, departments, tickets, ticket_messages, posts, post_attachments, post_reports, comments, reactions, ai_knowledge_documents).
   - departments: Nạp sẵn dữ liệu ban đầu cho 'Đoàn Thanh niên - Hội Sinh viên', 'Phòng Tuyển sinh & Truyền thông', 'Phòng Đào tạo', 'Khoa Công nghệ Thông tin', 'Khoa Ngoại ngữ'.
   - Bảng tickets: Hỗ trợ cột guest_name, guest_email, creator_id (nullable cho Guest), source_type ('DIRECT_FORM', 'CHAT_CONVERSION').
   - Bảng posts: Hỗ trợ cột status ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'HIDDEN'), approved_by, approved_at.
   - Bảng post_reports: id, post_id, reporter_id, reason, status ('PENDING', 'RESOLVED', 'DISMISSED'), created_at.
   - Bảng ai_knowledge_documents: id, title, file_url, department_id, indexed_status, created_at.

3. Locked Interface Contract (Hợp đồng code khóa cứng):
   - Viết sẵn định nghĩa Interface Java đầy đủ:
     + IUserService: Tìm user theo ID, lấy Department của Staff.
     + IStorageService: Hàm uploadFile(MultipartFile file, String folder), trả về AttachmentDTO.
     + IAiRagService: Hàm generateAnswer(String userQuestion, Long departmentId), trả về AiResponseDTO kèm trích dẫn nguồn tài liệu.
     + INotificationService: Hàm sendTicketAlert(...), sendPostReportAlert(...), sendFeedNotification(...).

4. Sprint Deliverables: Danh mục chức năng phải bàn giao qua từng Sprint.
```

---

## 🟢 PROMPT 4: Chi tiết hóa Task List & Tiêu chí Acceptance (`docs/team/dev-a-tasks.md` & `dev-b-tasks.md`)
**Mục tiêu:** Chuyển giao nhiệm vụ thành checklist có mã định danh, liên kết trực tiếp tới BRULE và có kịch bản test thủ công.

```text
Dựa trên dev-a-assignment.md và dev-b-assignment.md, hãy lập file docs/team/dev-a-tasks.md và docs/team/dev-b-tasks.md.

Mỗi task phải theo đúng cấu trúc sau:
- Checkbox: - [ ]
- Mã Task: [DEV-A-SPx-xx] hoặc [DEV-B-SPx-xx]
- Tên nhiệm vụ và File tạo mới/chỉnh sửa.
- Business Rules ràng buộc (tham chiếu mã BRULE trong brief.md).
- Acceptance Criteria (AC) dạng Given - When - Then.
- Manual Test Procedure (Các bước thao tác, Input mẫu, Output kỳ vọng trên giao diện và trong Database MySQL).

YÊU CẦU ĐẶC BIỆT:
- Dev A phải có task chi tiết về:
  + [DEV-A-SP2-01]: Tạo Ticket trực tiếp qua Form (hỗ trợ cả Guest nhập Email lẫn Student đã đăng nhập).
  + [DEV-A-SP2-02]: Chuyển đổi Chat thành Ticket và tính SLA due_date tự động.
  + [DEV-A-SP2-03]: Gửi Async Email thông báo trạng thái Ticket cho Guest & Student.
  + [DEV-A-SP2-04]: Chặn Staff Khoa CNTT truy cập Ticket của Khoa Ngoại ngữ / Đoàn trường (HTTP 403 / Redirect).
  + [DEV-A-SP4-01]: Tích hợp AI RAG Chatbot truy vấn tài liệu PDF/DB và tự động gợi ý tạo Ticket khi không chắc chắn.
- Dev B phải có task chi tiết về:
  + [DEV-B-SP3-01]: Form đăng bài Staff (Đoàn trường, Tuyển sinh) kèm đa tệp đính kèm (PDF, Excel, Video MP4).
  + [DEV-B-SP3-02]: Webhook Endpoint POST /api/v1/posts/webhook/video-rendered nhận video từ dự án Node.js tự động.
  + [DEV-B-SP3-03]: Form thảo luận của Student (tạo bài ở trạng thái PENDING_APPROVAL).
  + [DEV-B-SP3-04]: Giao diện và luồng duyệt bài viết (Staff/Admin Approve/Reject bài của Student).
  + [DEV-B-SP3-05]: Chức năng Báo cáo bài viết vi phạm (Report) và Trang quản lý Report của Admin/Staff.
```

---

## 🟢 PROMPT 5: Kịch bản Kiểm thử Tự động E2E với Playwright (`tests/e2e`)
**Mục tiêu:** Đóng vai QA Automation Engineer viết bộ kiểm thử chạy trình duyệt thực tế (Headless Browser) tự động kiểm tra toàn bộ luồng nghiệp vụ.

```text
Đóng vai trò Senior Automation QA Engineer. Hãy xây dựng bộ kiểm thử tự động End-to-End (E2E Test) hoàn chỉnh bằng Playwright (sử dụng TypeScript/JavaScript hoặc Java Playwright SDK) để kiểm thử hệ thống Spring Boot:

1. Thiết lập Cấu hình Playwright (playwright.config):
   - Cấu hình baseURL: "http://localhost:8080".
   - Bật trace recording, tự động chụp screenshot và lưu video khi test case thất bại.

2. Cấu trúc Page Object Model (POM):
   - AuthPage: Đăng nhập, đăng ký, đăng xuất.
   - StaffPostPage: Giao diện đăng bài của Cán bộ Tuyển sinh/Đoàn trường kèm upload đa tệp và nhận video từ Node.js.
   - StudentFeedPage: Giao diện thảo luận của Student (gửi bài chờ duyệt, comment, nút Report).
   - AiAssistantModal: Khung chat tư vấn AI RAG cho Guest/Student hỏi đáp tuyển sinh, học vụ.
   - ModerationPage: Trang duyệt bài và xử lý Báo cáo vi phạm của Staff/Admin.
   - TicketDashboardPage: Bảng điều khiển Ticket, tạo Ticket trực tiếp, nhận xử lý (Claim) và kiểm tra badge cảnh báo màu SLA.

3. Viết kịch bản kiểm thử E2E chi tiết cho các luồng:
   - Scenario 1 (Hỏi đáp Tuyển sinh với AI RAG & Tạo Ticket):
     Guest mở web -> Chat với AI hỏi về "Học phí và chỉ tiêu ngành CNTT" -> AI trích dẫn từ Đề án tuyển sinh trả lời -> Guest hỏi câu hỏi chuyên sâu cá nhân -> AI gợi ý tạo Ticket -> Guest điền email gửi ticket thành công.
   - Scenario 2 (Luồng duyệt bài và báo cáo vi phạm):
     Student đăng bài thảo luận -> Bài ở trạng thái Chờ duyệt -> Staff Đoàn trường duyệt bài -> Bài hiển thị trên Feed -> User khác bấm "Báo cáo vi phạm" -> Staff ẩn bài viết.
   - Scenario 3 (Multi-Context Ticket Flow):
     Mở đồng thời 2 Context trình duyệt:
     + Context 1 (Student/Guest): Gửi Ticket chọn Phòng Tuyển sinh.
     + Context 2 (Staff Tuyển sinh): Dashboard hiển thị real-time -> Claim và gán SLA -> Context 1 nhận cập nhật trạng thái ngay lập tức.
```

---

# PHẦN 3: NGUYÊN TẮC VÀNG ĐẢM BẢO KHÔNG XUNG ĐỘT KHI CODE
1. **Cô lập theo Module Package:** Dev A toàn quyền trong `module/ticket/`, `module/auth/` và `module/ai/`. Dev B toàn quyền trong `module/feed/`, `module/integration/` và `config/storage/`.
2. **Khóa cứng Database Schema trước khi Code:** Chốt toàn bộ file script SQL ban đầu (Prompt 3).
3. **Giao tiếp qua DTO và Locked Interface:** Luôn giao tiếp qua `IAiRagService`, `IUserService`, `IStorageService`, tuyệt đối không gọi trực tiếp Repository của module khác.