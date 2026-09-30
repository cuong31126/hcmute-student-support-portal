# 🚀 LỘ TRÌNH PHÁT TRIỂN & NHẬT KÝ TIẾN ĐỘ DỰ ÁN (QAUTE PORTAL)

> **Cổng Hỗ Trợ & Tư Vấn Học Vụ Sinh Viên HCMUTE (Spring Boot 3 + MySQL 8 + Thymeleaf + AI RAG)**  
> **Tài liệu nguồn hợp nhất:** `docs/progress/roadmap_and_logs.md`

---

## 📌 1. TỔNG QUAN TIẾN ĐỘ THEO SPRINT

| Sprint | Hạng mục cốt lõi | Trạng thái | Ghi chú kỹ thuật |
| :--- | :--- | :---: | :--- |
| **Sprint 1** | **Xác thực, Bảo mật & Phân quyền RBAC** | ✅ Hoàn thành | Spring Security 6, Session Cookie + JWT Filter, Đăng ký OTP qua Email, Chống can thiệp chéo (`Data Isolation`) giữa các Khoa. |
| **Sprint 2** | **Quản lý Ticket, Động cơ SLA & Gửi Mail Bất đồng bộ** | ✅ Hoàn thành | 2 kênh tiếp nhận (Sinh viên & Khách tra cứu bằng Guest Token), Tự động tính `due_date`, Bàn giao tiếp nhận xử lý, Gửi mail `@Async`, Đánh giá sao CSAT. |
| **Sprint 3** | **Bảng Tin Chính Thức, Diễn Đàn Sinh Viên & Webhook Video** | ✅ Hoàn thành | Video Hybrid (nhúng YouTube/Drive hoặc tải trực tiếp), Đa tệp văn phòng (tối đa 5 file), Thả tim (Like) & Bình luận bằng AJAX tức thì, Hàng đợi kiểm duyệt bài viết. |
| **Sprint 4** | **Trợ Lý Tri Thức Học Vụ & AI RAG Phân Tầng** | ✅ Hoàn thành | Tinh tuyển 300 FAQ chất lượng, Bộ nhớ Cache Caffeine ngắt tải sớm (70% lượt hỏi), Gemini Embedding + Cosine Similarity tính toán trên RAM CPU $\le 2\text{ms}$. |

---

## 🏛️ 2. CÁC TÀI LIỆU THIẾT KẾ KIẾN TRÚC ĐÃ CHỐT

### 2.1. Kiến Trúc AI RAG Phân Tầng (Tiered RAG Architecture)
- **Tầng 0 (Regex & Static Rule):** Lọc các câu chào hỏi, thông tin đường dây nóng khẩn cấp trong $\le 10\text{ms}$.
- **Tầng 1 (Local In-Memory Cache):** Lưu trữ 300 FAQs tinh tuyển và các câu hỏi phổ biến trên RAM; tra cứu Cosine Similarity trong $\le 5\text{ms}$.
- **Tầng 2 (Smart FAQ Matcher):** Thuật toán kết hợp Token Matching + Levenshtein + Jaccard Index.
- **Tầng 3 (Fallback Gemini 1.5/2.0 Flash):** Chỉ kích hoạt khi các tầng trên có độ tương đồng thấp, context được tinh gọn dưới 300 từ nhằm tối ưu chi phí và độ trễ $p95 < 1.5\text{s}$.

### 2.2. Kiến Trúc Bảng Tin & Diễn Đàn Sinh Viên (Feed & Forum Architecture)
- **Cơ chế Video Hybrid:** Hỗ trợ nhúng link trực tiếp từ YouTube (hỗ trợ cả unlisted link) hoặc Google Drive giúp tiêu tốn 0 byte dung lượng lưu trữ máy chủ và tận dụng băng thông CDN không giới hạn.
- **Quản lý Đa tệp đính kèm (`PostAttachment`):** Cho phép Cán bộ đính kèm tối đa 5 file văn phòng (`.pdf`, `.docx`, `.xlsx`, `.zip`) với kiểm tra Magic Bytes chống file giả mạo.
- **Tương tác Thả tim (Like/Unlike Toggle):** Bảng `post_reactions` / `likes` lưu danh tính người tương tác, cơ chế toggle không reload trang (AJAX Fetch API).
- **Bộ lọc & Tìm kiếm:** Tích hợp phân trang `Pageable`, tìm kiếm theo tiêu đề/nội dung và lọc theo từng Khoa/Phòng ban.

---

## 🛠️ 3. NHẬT KÝ SỬA LỖI & TỐI ƯU HÓA ĐẶC THÙ (BUG POSTMORTEMS)

1. **Vá lỗi LazyInitializationException (Kỷ luật OSIV = false):**
   - Chuyển đổi 100% dữ liệu truyền sang View Thymeleaf thành DTO/Record được ánh xạ trọn vẹn trong tầng Service có `@Transactional(readOnly = true)`.
   - Tuyệt đối không gọi Lazy Collection ngoài phạm vi Transaction.
2. **Khắc phục lỗi Hibernate Orphan Removal 500:**
   - Tuyệt đối không gán `entity.setList(newList)` trên quan hệ `@OneToMany(orphanRemoval = true)` đang được quản lý bởi Persistence Context. Sử dụng `list.clear()` và `list.addAll(newList)` thay thế.
3. **Vá lỗi SpEL Access Control & IDOR:**
   - Tối ưu hóa `DepartmentAccessEvaluator` và bảo vệ truy cập dữ liệu chéo giữa các Khoa bằng SpEL expression `@PreAuthorize("@deptSecurity.canAccessDepartment(#deptId)")`.

---

## 🎯 4. CÁC HẠNG MỤC CÒN THIẾU CẦN TRIỂN KHAI TIẾP THEO

- [x] **SLA Engine Scheduler (`@Scheduled`):**
  - Quét định kỳ tự động đóng Ticket sau 72h ở trạng thái `RESOLVED`.
  - Tự động phát hiện Ticket quá hạn và đổi trạng thái sang `OVERDUE`, gửi email cảnh báo SLA đến Cán bộ phụ trách.
- [x] **Admin Web Dashboard & User Management (`/admin/**`):**
  - Màn hình Dashboard quản trị thống kê SLA toàn trường (`/admin/dashboard` & `/admin`): Đo lường SLA Compliance Rate, phân tích ma trận hiệu suất từng Khoa/Phòng ban, phân bổ mức độ ưu tiên SLA và bảng cảnh báo các ticket vi phạm hạn chót.
  - Quản trị danh sách người dùng (`/admin/users`): Tìm kiếm, lọc theo vai trò/khoa/trạng thái, phân trang, toggle Khóa/Mở tài khoản bằng AJAX + Right-side Toast, Modal chỉnh sửa vai trò và gán đơn vị trực thuộc.
  - Quản lý danh mục Khoa/Phòng ban (`/admin/departments`): Xem thống kê nhân sự và ticket, thêm mới/chỉnh sửa đơn vị và toggle hoạt động qua AJAX.
- [ ] **CSAT Rating cho Guest:** Bổ sung form đánh giá hài lòng trực tiếp trên trang tra cứu `guest-track.html`.
