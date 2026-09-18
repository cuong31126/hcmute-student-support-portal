# BÁO CÁO TIẾN ĐỘ DỰ ÁN QAUTE PORTAL
- **Thời điểm cập nhật:** 18/09/2026
- **Trạng thái kiểm thử:** 26/26 Tests Passed (BUILD SUCCESS - 100% TDD Green Phase)

---

## 1. TIẾN ĐỘ ĐÃ HOÀN THÀNH

### Module 1: Xác thực, Phân quyền & Quản lý Người dùng (Sprint 1)
- [x] Cấu hình Spring Security 6, session cookie, CSRF, BCrypt (`SecurityConfig.java`).
- [x] Phân quyền đa vai trò: Sinh viên (`ROLE_STUDENT`), Cán bộ (`ROLE_STAFF`), Quản trị viên (`ROLE_ADMIN`, `ROLE_SUPER_ADMIN`).
- [x] Đăng ký, đăng nhập tài khoản sinh viên và cán bộ qua Form Login.
- [x] Vá lỗ hổng và tối ưu evaluator SpEL (`DepartmentAccessEvaluator.java`): chống NPE, an toàn transaction, hỗ trợ đa vai trò quản trị.

### Module 2: Quản lý Ticket, SLA & Email Thông báo (Sprint 2)
- [x] Tiếp nhận Ticket qua 2 kênh: Sinh viên đã đăng nhập và Khách vãng lai (Guest tra cứu qua token bí mật).
- [x] Động cơ tính SLA tự động theo độ ưu tiên (`URGENT` 24h, `HIGH` 48h, `MEDIUM` 72h, `LOW` 7 ngày).
- [x] Gửi Email bất đồng bộ (`@Async`) thông báo tạo Ticket, phân công cán bộ, giải quyết Ticket.
- [x] Vá lỗ hổng IDOR xem chéo Ticket giữa các Khoa (`StaffTicketController.java`, `StaffTicketWebController.java`).
- [x] Tối ưu hóa truy vấn phân quyền với JPA Projection (`TicketAccessAuthInfo`), giảm tải RAM và tránh `LazyInitializationException`.

### Module 3: Bảng tin Chính thức, Diễn đàn & Kiểm duyệt (Sprint 3)
- [x] Bảng tin chính thức: Cán bộ đăng bài thông báo, sinh viên xem danh sách và chi tiết bài viết.
- [x] Diễn đàn sinh viên: Đăng bài thảo luận, cơ chế kiểm duyệt (`PENDING_APPROVAL` -> `APPROVED` / `REJECTED`).
- [x] Tương tác bài viết: Like, Comment thảo luận, Báo cáo vi phạm (`PostReport`).
- [x] Dashboard kiểm duyệt dành cho Cán bộ (`/moderation/pending`, `/moderation/reports`).
- [x] Tích hợp Webhook bảo mật HMAC-SHA256 nhận video render tự động từ Node.js microservice.

### Module 4: Trợ lý AI Học vụ & FAQ Semantic Search (Sprint 4)
- [x] Nạp dữ liệu câu hỏi học vụ mẫu vào bộ nhớ cache.
- [x] Thuật toán tìm kiếm tương đồng (Jaccard + Levenshtein + Keyword Matching).
- [x] Giao diện Widget Chatbot hỗ trợ sinh viên tức thì và trang tra cứu FAQ theo Khoa/Phòng.
- [x] **[MỚI] Tinh gọn bộ FAQ (Curated 300 FAQs)**: Lọc sạch từ 42.000 dòng xuống 2.400 dòng tại `docs/dataset/faq_dataset_curated.json`, bảo toàn các câu trả lời thực chất, kèm mốc thời gian gốc để phục vụ RAG.
- [x] **[MỚI] Thiết kế TDD Spec AI RAG Phân tầng (Tiered RAG)**: Đã hoàn tất tài liệu phân tích nghiệp vụ, bảng database và bộ test TDD tại `docs/progress/tiendo2.md`.

---

## 2. NHỮNG PHẦN ĐANG CÒN THIẾU HOẶC CẦN HOÀN THIỆN
1. **Module AI RAG Phân tầng trên MySQL (Sprint 4+):** Cần triển khai mã nguồn theo đúng TDD Spec trong `tiendo2.md` (VectorMathUtils, GeminiEmbeddingClient, RagKnowledgeService, AdminKnowledgeController).
2. **Xác thực Email OTP khi Đăng ký:** Hiện tại tài khoản đăng ký được kích hoạt ngay, chưa có bước gửi OTP 6 số vào email để xác minh.
3. **Cấu hình Base URL:** Một số link gửi qua email vẫn đang hardcode `http://localhost:8080` thay vì đọc từ file cấu hình `application.properties`.
4. **Phân trang (Pagination):** Các danh sách Ticket và Post lớn chưa áp dụng `Pageable` để tối ưu tải trang.
5. **Cloudinary Storage & Magic Byte Check:** Hiện tại lưu trữ file đính kèm đang chạy cục bộ (`LocalStorageServiceImpl`), chưa có tầng fallback Cloudinary và kiểm tra file giả mạo đuôi.

---

## 3. ĐỀ XUẤT LỘ TRÌNH PHÁT TRIỂN TIẾP THEO

### Bước 1: Triển khai Module AI RAG Phân tầng theo TDD (Đã chốt thiết kế tại `tiendo2.md`)
- **Mục tiêu:** Tạo bảng `knowledge_documents` & `knowledge_chunks`, viết `VectorMathUtils`, tích hợp Gemini Embedding và API upload PDF công văn Tầng 1.
- **Lợi ích:** Hệ thống Chatbot trả lời chuẩn xác theo quy chế mới nhất 2026, chống ảo giác và chịu tải 1.000 sinh viên.

### Bước 2: Cấu hình Base URL động cho Email & Link hệ thống
- **Mục tiêu:** Chuyển các chuỗi `http://localhost:8080` ở `StaffTicketWebController` và `TicketService` sang biến `app.base-url` trong `application.properties`.

### Bước 3: Hoàn thiện luồng Xác thực OTP Email khi Đăng ký
- **Mục tiêu:** Sinh viên đăng ký -> Nhận OTP 6 số qua email -> Nhập mã kích hoạt tại `verify-otp.html`.
