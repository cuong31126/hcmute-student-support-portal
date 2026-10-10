# 📋 KẾ HOẠCH & ĐẶC TẢ CHI TIẾT HOÀN THIỆN HỆ THỐNG (TIẾN ĐỘ 13) - QAUTE PORTAL

> **MỤC TIÊU CHÍNH:** Hoàn thiện trải nghiệm Đánh giá hài lòng (CSAT) & Đóng Ticket, Rà soát AI Chatbot Widget & Luồng Chuyển đổi Chat sang Ticket, Xây dựng hoàn chỉnh Luồng kiểm duyệt bài viết Diễn đàn sinh viên (Forum Approval), và Chuẩn hóa triệt để giao diện người dùng (Clean Human Academic UI - Loại bỏ hoàn toàn icon/emoji dư thừa máy móc).

---

## 🏛️ 1. QUY CHUẨN THIẾT KẾ GIAO DIỆN & TRẢI NGHIỆM (STRICT UI/UX RULES)

1. **Chuẩn Mực Thiết Kế Chuyên Nghiệp (Anti-Robot / Human-Centric Design):**
   - Tuyệt đối loại bỏ các biểu tượng cảm xúc (emoji) và icon trang trí rải rác do AI tự sinh gây cảm giác máy móc, thiếu nghiêm túc.
   - Chỉ sử dụng biểu tượng Bootstrap Icons ở các nút bấm có chức năng rõ ràng (Gửi, Tải file, Quay lại, Tìm kiếm, Đóng).
2. **Ngôn Ngữ Học Thuật & Hành Chính Chuẩn Mực:**
   - Câu từ ngắn gọn, gãy gọn, đúng văn phong Cổng dịch vụ công trực tuyến của Trường Đại học Sư phạm Kỹ thuật TP.HCM (HCMUTE).
3. **Tương Tác Không Tải Lại Trang (Zero Full-Page Refresh):**
   - Tất cả các thao tác đánh giá sao, đóng ticket, duyệt bài diễn đàn, gửi tin nhắn chatbot đều thực hiện qua AJAX / Fetch API mượt mà kèm Toast thông báo góc phải màn hình.

---

## 🎯 2. DANH MỤC 4 HẠNG MỤC ƯU TIÊN THỰC HIỆN

---

### 📌 HẠNG MỤC 1: Trải Nghiệm Đóng Ticket & Đánh Giá Mức Độ Hài Lòng (CSAT 1 - 5 Sao)

#### A. Phân tích thực trạng
- Khi Cán bộ hỗ trợ xử lý xong và chuyển trạng thái vé sang `RESOLVED`, Sinh viên (hoặc Khách vãng lai qua Guest Track) cần có một khu vực tương tác trực quan để:
  1. Đánh giá số sao hài lòng (từ 1 sao đến 5 sao).
  2. Gửi ý kiến phản hồi hoặc góp ý bổ sung.
  3. Bấm xác nhận hoàn tất để chuyển trạng thái vé sang `CLOSED`.
- Hiện tại, luồng đánh giá CSAT chưa được đồng bộ hoàn toàn giữa giao diện Chi tiết Ticket (`/tickets/detail/{id}`) và Tra cứu Khách (`/tickets/guest-track`), làm số liệu CSAT trên Dashboard Quản trị chưa phản ánh đầy đủ.

#### B. Giải pháp thiết kế & kỹ thuật
1. **Giao diện Chi tiết Ticket (`detail.html` & `guest-track.html`):**
   - Khi trạng thái Ticket là `RESOLVED`: Hiển thị khối thông báo nổi bật kèm bộ chọn 5 sao tương tác mượt mà (Star Rating Component) và ô nhập nhận xét.
   - Nút hành động: *"Xác Nhận Đã Giải Quyết Xong & Đóng Yêu Cầu"* (`CLOSED`).
   - Sau khi gửi đánh giá qua AJAX: Giao diện chuyển ngay sang trạng thái `CLOSED` kèm hiển thị số sao đã đánh giá, vô hiệu hóa form nhập để tránh gửi lặp.
2. **Backend Service (`TicketService.java` & `TicketWebController.java`):**
   - Cung cấp API đánh giá đồng nhất cho cả Sinh viên đăng nhập (`POST /tickets/{id}/rate`) và Khách vãng lai (`POST /tickets/guest-track/api/rate`).
   - Cập nhật trường `rating`, lưu vết vào `TicketHistory`, tự động chuyển trạng thái `status = 'CLOSED'` và lưu thời điểm `closed_at`.

---

### 📌 HẠNG MỤC 2: Rà Soát AI Chatbot Widget & Luồng Chuyển Đổi Chat $\rightarrow$ Ticket

#### A. Phân tích thực trạng
- Widget AI nổi ở góc phải màn hình là điểm chạm đầu tiên của Sinh viên & Khách.
- Cần đảm bảo giao diện Widget trang nhã, không màu mè sặc sỡ, và khi AI không giải đáp được hoặc câu hỏi phức tạp, hệ thống phải có nút chuyển tiếp dữ liệu thông minh sang Form Tạo Ticket.

#### B. Giải pháp thiết kế & kỹ thuật
1. **Tối ưu giao diện Widget Chatbot:**
   - Tông màu chuẩn: Deep Slate (`#111827`) và Harvard Crimson (`#A51C30`), phông chữ `Inter`, độ tương phản WCAG AA.
   - Hiển thị rõ nguồn trích dẫn quy chế / tài liệu tham khảo khi AI phản hồi từ kho tri thức RAG.
2. **Nút Chuyển Đổi Nhanh Sang Ticket:**
   - Dưới mỗi câu trả lời của AI hoặc khi AI nhận diện câu hỏi cần chuyên viên can thiệp: Hiển thị nút *"Tạo Phiếu Tư Vấn Tới Cán Bộ"*.
   - Khi người dùng bấm vào nút: Chuyển hướng sang `/tickets/create` kèm các tham số `initialTitle`, `initialDescription`, `departmentId` được điền tự động từ ngữ cảnh cuộc trò chuyện.

---

### 📌 HẠNG MỤC 3: Luồng Kiểm Duyệt Bài Viết Diễn Đàn Sinh Viên (`/feed/forum`)

#### A. Phân tích thực trạng
- Diễn đàn sinh viên phục vụ chia sẻ học tập nhưng bắt buộc phải có cơ chế kiểm duyệt nội dung văn hóa học đường (`PENDING_APPROVAL` $\rightarrow$ `APPROVED`).
- Cán bộ Khoa và Quản trị viên cần có tab/bộ lọc riêng để xem nhanh các bài viết đang chờ duyệt và thao tác Duyệt / Từ chối bài viết ngay trên giao diện.

#### B. Giải pháp thiết kế & kỹ thuật
1. **Bổ sung Tab "Chờ Duyệt" cho Staff/Admin trên trang Forum:**
   - Khi tài khoản có quyền `ROLE_STAFF` hoặc `ROLE_ADMIN`: Hiển thị thêm Tab *"Duyệt Bài Viết"* với badge số lượng bài viết đang ở trạng thái `PENDING_APPROVAL`.
2. **Nút Hành Động Phê Duyệt Nhanh (AJAX):**
   - Mỗi bài viết chờ duyệt có 2 nút: *"Phê Duyệt"* (chuyển sang `APPROVED`) và *"Từ Chối / Xóa"*.
   - Thực hiện qua Fetch API không reload trang, cập nhật ngay giao diện và hiển thị Toast thông báo.

---

### 📌 HẠNG MỤC 4: Rà Soát Toàn Bộ Giao Diện & Chuẩn Hóa Typography (Clean Human UI)

#### A. Mục tiêu
- Quét qua tất cả các trang chính của hệ thống để loại bỏ triệt để các emoji, icon máy móc dư thừa, căn chỉnh lề, khoảng cách và chuẩn hóa bảng màu theo đúng `.agents/skills/academic-ui-ux/SKILL.md`.

#### B. Danh sách màn hình rà soát:
1. **Trang Đăng nhập / Đăng ký / Quên mật khẩu (`/login`, `/register`, `/forgot-password`):** Layout Split-Screen tràn viền, loại bỏ icon màu mè rải rác.
2. **Trang Chủ Portal (`/`):** Hero section trang nghiêm, thanh tìm kiếm thông minh, thẻ danh mục dịch vụ học vụ gãy gọn.
3. **Trang Chi Tiết Yêu Cầu (`/tickets/detail/{id}`):** Luồng trao đổi tin nhắn rõ ràng giữa Cán bộ và Sinh viên, hiển thị tệp đính kèm sạch sẽ.
4. **Trang Hồ Sơ Cá Nhân (`/profile`):** Thông tin sinh viên, đổi mật khẩu, quản lý khoa/lớp chuẩn mực.

---

## 📅 3. KẾ HOẠCH TRIỂN KHAI & PHÂN CÔNG CÔNG VIỆC

| Bước | Nội dung công việc | File ảnh hưởng | Trạng thái |
|:---:|---|---|:---:|
| **1** | Hoàn thiện Modal/Box Đánh giá sao CSAT & Đóng Ticket tại trang Chi tiết Ticket và Guest Track | `src/main/resources/templates/ticket/detail.html`<br>`src/main/resources/templates/ticket/guest-track.html`<br>`src/main/java/com/school/counseling/module/ticket/controller/TicketWebController.java` | ⏳ Chờ thực hiện |
| **2** | Rà soát và hoàn thiện Widget AI Chatbot, tối ưu luồng chuyển tiếp Chat sang Ticket | `src/main/resources/templates/layout/main.html`<br>`src/main/resources/static/js/ai-chat-widget.js` (hoặc inline fragment) | ⏳ Chờ thực hiện |
| **3** | Xây dựng Tab và tính năng Duyệt / Từ chối bài viết diễn đàn cho Cán bộ & Admin | `src/main/resources/templates/feed/forum.html`<br>`src/main/java/com/school/counseling/module/feed/controller/ForumController.java`<br>`src/main/java/com/school/counseling/module/feed/service/PostService.java` | ⏳ Chờ thực hiện |
| **4** | Rà soát Clean UI toàn bộ hệ thống: Loại bỏ emoji dư thừa, chuẩn hóa typography học đường | Toàn bộ các template HTML trong `src/main/resources/templates/` | ⏳ Chờ thực hiện |
| **5** | Kiểm tra biên dịch hệ thống `mvn test-compile` và nghiệm thu hoàn tất | Toàn bộ dự án | ⏳ Chờ thực hiện |
