# 📋 KẾ HOẠCH & ĐẶC TẢ THIẾT KẾ CHI TIẾT (TIẾN ĐỘ 12) - QAUTE PORTAL

> **MỤC TIÊU CHÍNH:** Tối ưu hóa trải nghiệm tạo yêu cầu (Ticket Creation), Tự động hóa SLA & Phân loại chủ đề theo Khoa, Xử lý chuyển giao vé quá hạn cho Quản trị viên (Admin), Thêm kênh trao đổi nội bộ giữa Cán bộ và Admin, Triệt tiêu lỗi trùng lặp thông báo, Chuẩn hóa báo cáo đồ án Word (loại bỏ Activity Diagram, tinh giản UML, bảng trắng đen), Loại bỏ tính năng Microservice Node.js Video Generator (chuyển về dạng chú thích), Giới hạn tối đa quyền truy cập của Guest (không tạo ticket, không chatbox) và Nâng cấp cơ chế Staff kiểm duyệt/xem lại các bài viết tố cáo bị ẩn.

---

## 🏛️ 1. NGUYÊN TẮC THIẾT KẾ GIAO DIỆN & BẢO MẬT BẮT BUỘC (STRICT SYSTEM RULES)

1. **Hạn chế tối đa Icon và Emoji dư thừa (Anti-Robot Design):**
   - Loại bỏ các icon trang trí, biểu tượng cảm xúc rải rác không cần thiết do AI tự sinh làm giao diện thiếu chuyên nghiệp.
   - Chỉ sử dụng icon Bootstrap chuẩn ở các nút hành động chính (Send, Paperclip, Back, Search).
2. **Tinh giản câu chữ & Tránh dài dòng (Zero Clutter):**
   - Không thêm thắt các đoạn giải thích mang tính máy móc, không chèn các thông tin phụ gây rối mắt.
   - Văn phong hành chính học đường chuẩn mực, trang nhã, đúng phong cách Cổng dịch vụ công của Đại học Sư phạm Kỹ thuật TP.HCM (HCMUTE).
3. **Trải nghiệm mượt mà, không giật lag (AJAX First):**
   - Thao tác tương tác nhanh gọn, cập nhật trực quan, không hiển thị nhiều hộp thông báo chồng chéo.
4. **Giới hạn nghiêm ngặt khách vãng lai (Guest Isolation):**
   - Khách vãng lai (chưa đăng nhập) chỉ được xem thông tin công khai (FAQ, Bảng tin, Diễn đàn ở chế độ đọc).
   - Tuyệt đối cấm Guest tạo Ticket hoặc tương tác với Chatbot AI. Mọi nỗ lực truy cập đều bị chặn và chuyển hướng về trang Đăng nhập (`/auth/login`).

---

## 🎯 2. DANH MỤC CÁC HẠNG MỤC VÀ KẾT QUẢ TRIỂN KHAI

### 📌 HẠNG MỤC 1: Tự động Phân loại Ưu tiên / SLA theo Chủ đề & Ẩn Lựa chọn Thủ công
- **Thực trạng & Giải pháp:** Dropdown chọn Priority thủ công đã bị ẩn. Hệ thống hiển thị Radio chọn Chủ đề học vụ theo Khoa/Phòng ban và tự động tính toán SLA (URGENT - 24h, HIGH - 48h, MEDIUM - 72h, LOW - 5 ngày).
- **Trạng thái:** ✅ Đã hoàn tất và nghiệm thu.

### 📌 HẠNG MỤC 2: Cơ chế Tự động Chuyển giao Vé Quá hạn cho Admin & Định danh Đơn vị Chậm trễ
- **Thực trạng & Giải pháp:** Động cơ `SlaSchedulerService` tự động quét các ticket `OPEN` bị trễ hạn SLA, chuyển quyền xử lý sang Admin, gia hạn thêm +24h giải quyết khẩn cấp và lưu vết `origin_department_id` phục vụ kiểm điểm kỷ luật.
- **Trạng thái:** ✅ Đã hoàn tất và nghiệm thu.

### 📌 HẠNG MỤC 3: Kênh Trao đổi & Nhắn tin Nội bộ giữa Cán bộ và Admin (Staff - Admin Messaging)
- **Thực trạng & Giải pháp:** Tích hợp Modal nhắn tin nhanh hai chiều trên Bàn làm việc Cán bộ (`/staff/tickets`, `/staff/dashboard`) và Bảng điều khiển Quản trị (`/admin/dashboard`) qua `InternalMessageRestController`.
- **Trạng thái:** ✅ Đã hoàn tất và nghiệm thu.

### 📌 HẠNG MỤC 4: Khắc phục Triệt để Lỗi Trùng lặp Thông báo Tạo Vé
- **Thực trạng & Giải pháp:** Xóa khối alert thừa tại `ticket/my-tickets.html`, gom thông báo flash message duy nhất tại `layout/main.html`.
- **Trạng thái:** ✅ Đã hoàn tất và nghiệm thu.

### 📌 HẠNG MỤC 5: Hiệu đính File Word Báo Cáo Đồ Án (`BaoCao_KhaoSat_Va_ThietKe_QAUTE_Portal_ban1.docx`)
- **Yêu cầu:** 
  1. Loại bỏ Biểu đồ Hoạt động (Activity Diagrams) khỏi Báo cáo và Mục lục.
  2. Tinh giản UML, giảm over-specification ở Chương 1 & 2; tập trung vào Biểu đồ Tuần tự (Sequence Diagrams) và Sơ đồ quan hệ thực thể (ERD).
  3. Định dạng lại toàn bộ 21 bảng sang phong cách Bảng Trắng Đen (Black & White Academic Style).
- **Kết quả thực hiện:**
  - Đã loại bỏ mục `3.6. Biểu đồ Hoạt động (Activity Diagrams)` và hình `Hình 3-11. Activity Diagram AD01` trong tài liệu cũng như Mục lục.
  - Sửa đổi nội dung phương pháp mô hình hóa ở Chương 1, chuyển trọng tâm mô tả luồng sang Biểu đồ Tuần tự (Sequence Diagrams) và ERD.
  - Cập nhật SD04 loại bỏ tích hợp Node.js Video Webhook, chuyển sang đính kèm đa văn bản quy chế.
  - Đánh số lại mục 3.7 (ERD 12 bảng) thành 3.6 trong toàn văn và Mục lục.
  - Chuyển đổi toàn bộ 21 bảng từ màu Crimson `#A51C30` sang chuẩn học thuật Trắng Đen: Tiêu đề nền `#F0F0F0`, viền nét đơn đen `#000000`, chữ đen đậm `#000000`, dòng so le `#FFFFFF` và `#FAFAFA`.
- **Trạng thái:** ✅ Đã hoàn tất và cập nhật trực tiếp vào file docx.

### 📌 HẠNG MỤC 6: Loại bỏ Tính năng Video Generator (Microservice Node.js)
- **Yêu cầu:** Loại bỏ tính năng tự động sinh video tóm tắt 40s bằng Node.js, chuyển toàn bộ mã nguồn liên quan về dạng chú thích (commented out).
- **Kết quả thực hiện:**
  - Chuyển toàn bộ nội dung class `VideoIntegrationService.java`, `NodejsWebhookController.java`, `VideoWebhookPayload.java` sang khối comment `/* ... */`.
  - Trong `PostService.java`: Vô hiệu hóa injection `videoIntegrationService`, loại bỏ logic gọi trigger video trong `createOfficialPost`, và chuyển phương thức `handleVideoWebhookCallback` về dạng chú thích.
  - Trong test: Vô hiệu hóa mock và các unit test `TDD-01`, `TDD-02`, `TDD-03` trong `PostServiceTest.java` và toàn bộ `NodejsWebhookControllerTest.java`.
  - Trên giao diện: Ẩn huy hiệu video tự sinh trên danh sách tin tức.
- **Trạng thái:** ✅ Đã hoàn tất và biên dịch thành công.

### 📌 HẠNG MỤC 7: Giới hạn Tối đa Khả năng Truy cập của Guest (Không tạo Ticket, Không Chatbox)
- **Yêu cầu:** Giới hạn nghiêm ngặt Guest (khách chưa đăng nhập): không thể tạo ticket và không thể dùng chatbot AI.
- **Kết quả thực hiện:**
  - **Bảo mật Back-end (`SecurityConfig.java`):** Chuyển `/api/v1/ai/**`, `/api/v1/tickets/**`, `/tickets/create`, `/ws-chat/**` vào khu vực yêu cầu bắt buộc đăng nhập `.authenticated()`. Khách vãng lai gọi API sẽ bị chặn 302 chuyển hướng về `/auth/login` (hoặc 401/403).
  - **Phân quyền Controller:** Đặt `@PreAuthorize("isAuthenticated()")` trên các endpoint tạo ticket trong `TicketWebController.java` (`GET /tickets/create` và `POST /tickets/create`).
  - **Giao diện Tạo Ticket (`create.html`):** Xóa bỏ khối thông tin Guest (`isAnonymous()`), form chỉ dành riêng cho sinh viên / thành viên đã đăng nhập.
  - **Thanh điều hướng (`navbar.html`):** Ẩn mục "Gửi Ticket" đối với Guest bằng `sec:authorize="isAuthenticated()"`.
  - **Chatbot AI (`layout/main.html` & `chat-widget.html`):** Chỉ hiển thị widget nổi đối với người dùng đã xác thực (`sec:authorize="isAuthenticated()"`). Với Guest, widget hoàn toàn không xuất hiện trên DOM.
  - **Trang chủ (`home.html`):** Nút "Hỏi Trợ Lý AI 24/7" trên Hero banner tự động kiểm tra: nếu chưa đăng nhập sẽ chuyển hướng sang trang `/auth/login`.
- **Trạng thái:** ✅ Đã hoàn tất và kiểm thử thành công.

### 📌 HẠNG MỤC 8: Kiểm thử & Nâng cấp Quản lý Bài viết Tố cáo Bị Ẩn (Staff Moderation)
- **Yêu cầu:** Kiểm tra phần bài viết tố cáo bị ẩn xem cán bộ (Staff) có xem được không, đảm bảo Staff có thể theo dõi và quản lý các bài viết này.
- **Kết quả phân tích & giải pháp nâng cấp:**
  - Trước đây, khi Staff bấm "Ẩn bài" (`HIDE_POST`), trạng thái bài viết đổi sang `HIDDEN` và báo cáo đổi sang `RESOLVED`. Do trang `/moderation/reports` mặc định chỉ lọc `status=PENDING`, báo cáo bài viết bị ẩn lập tức biến mất khỏi danh sách của Staff.
  - **Bổ sung Bộ lọc trạng thái trên `reports-list.html`:**
    + Tab 1: **"Chờ Xử Lý (PENDING)"** kèm số lượng badge báo cáo mới.
    + Tab 2: **"Đã Xử Lý / Bài Viết Đã Ẩn (RESOLVED)"** kèm badge thống kê.
    + Tab 3: **"Tất Cả (ALL)"**.
  - **Hiển thị trực quan bài viết bị ẩn:** Cột "Bài Viết Bị Báo Cáo" hiển thị nhãn đỏ `ĐÃ ẨN` đối với bài viết có `post.status == 'HIDDEN'`, kèm nút **"Xem bài"** mở Modal hiển thị toàn văn tiêu đề, nội dung, tác giả bài viết và lý do tố cáo chi tiết.
  - **Tính năng Khôi phục bài viết:** Bổ sung endpoint `POST /moderation/reports/{id}/unhide` và nút **"Khôi Phục Bài"** giúp Cán bộ bỏ ẩn bài viết nếu sinh viên bị tố cáo oan/nhầm lẫn.
  - **Kiểm thử tự động:** Bổ sung test case trong `ModerationControllerTest` (`testViewResolvedReportsShowingHiddenPosts`) và `PostServiceTest` (`shouldResolveReportAndHidePostSuccessfully`, `shouldUnhidePostAndRestoreToApproved`), xác nhận 100% test pass.
- **Trạng thái:** ✅ Đã hoàn tất và kiểm thử thành công.

### 📌 HẠNG MỤC 9: Sửa Triệt Để Lỗi 500 Server Error tại `/moderation/reports` và Khi Xử Lý Báo Cáo
- **Thực trạng & Nguyên nhân gốc rễ:** 
  1. Khi Staff hoặc Admin truy cập `/moderation/reports`, trang bị lỗi `500 - Sự Cố Máy Chủ` (`EntityNotFoundException: Entity 'com.school.counseling.module.feed.entity.Post' with identifier value '104' does not exist`).
     - *Nguyên nhân:* Có bài viết bị xóa mềm (`is_deleted = true`). Hibernate 6 áp dụng `@SQLRestriction("is_deleted = false")` khiến dòng `posts` trả về null nhưng khóa ngoại vẫn tồn tại trên `post_reports`.
  2. Khi Cán bộ bấm xử lý báo cáo có bài viết đã bị xóa (ví dụ Báo cáo #2 có bài viết đã bị dọn dẹp), hệ thống tiếp tục văng lỗi 500:
     - *Nguyên nhân A:* `PostService.resolveReport` gọi trực tiếp `post.setStatus(...)` mà không kiểm tra `post != null` dẫn đến `NullPointerException`.
     - *Nguyên nhân B:* Do Hibernate nạp `post = null` vào Entity, khi gọi `postReportRepository.save(report)`, câu lệnh SQL Update cố tình ghi đè `post_id = NULL` vi phạm ràng buộc toàn vẹn MySQL `Column 'post_id' cannot be null`.
- **Giải pháp xử lý toàn diện:**
  - Bổ sung `@NotFound(action = NotFoundAction.IGNORE)` trên các quan hệ `@ManyToOne` của `PostReport` (`post`, `reporter`, `resolvedBy`) và `Post` (`author`, `approvedBy`).
  - Đánh dấu `@JoinColumn(name = "post_id", updatable = false)` và `reporter_id` với `updatable = false` trên `PostReport`: ngăn chặn Hibernate ghi đè null vào khóa ngoại khi thực hiện Update.
  - Bổ sung kiểm tra an toàn `if (post != null)` trong `PostService.resolveReport`, đảm bảo việc cập nhật trạng thái báo cáo sang `RESOLVED` diễn ra hoàn hảo ngay cả khi bài viết gốc đã bị dọn dẹp.
  - Trên giao diện `reports-list.html`: đối với bài viết đã bị xóa, hiển thị nút **"Đóng Báo Cáo"** (thay vì nút Ẩn Bài / Khóa User vô nghĩa) giúp cán bộ hoàn tất xử lý chỉ với 1 click.
- **Trạng thái:** ✅ Đã khắc phục hoàn toàn và nghiệm thu trực tiếp trên Báo cáo #2 (HTTP 302 chuyển hướng thành công, Toast thông báo xanh góc phải hiển thị mượt mà).

### 📌 HẠNG MỤC 10: Triệt Tiêu Lỗi Hiện Thông Báo 2 Lần & Nâng Cấp Hệ Thống Toast Góc Phải (Academic UI/UX)
- **Thực trạng & Nguyên nhân gốc rễ:**
  - Khi đăng thông báo ở Bảng tin hoặc phê duyệt bài viết ở Diễn đàn, người dùng nhận thông báo thành công 2 lần xếp chồng lên nhau.
  - Nguyên nhân: Cả layout cha (`layout/main.html`) và các view con (`pending-posts.html`, `reports-list.html`, `official-detail.html`, `staff-dashboard.html`) đều cùng khai báo khối `<div th:if="${successMessage}" class="alert alert-success">`.
- **Giải pháp xử lý chuẩn Academic UI/UX:**
  - Xóa bỏ toàn bộ các khối alert lặp lại trong các file giao diện con.
  - Xóa bỏ banner alert đẩy vỡ layout trong nội dung trang tại `layout/main.html`.
  - Tích hợp **Toast Container góc trên cùng bên phải (`#toastContainer`)** cố định (`position-fixed top-0 end-0 p-3`, `z-index: 1090`) tại `layout/main.html` theo đúng quy chuẩn `.agents/skills/academic-ui-ux/SKILL.md`.
  - Hỗ trợ đầy đủ Toast Thành công (`app-toast text-bg-success`), Toast Lỗi (`text-bg-danger`) và Toast Thông tin (`text-bg-primary`), tự động ẩn mềm mại sau 4 giây (`delay: 4000`) hoặc đóng bằng nút X.
- **Trạng thái:** ✅ Đã hoàn tất và nghiệm thu.

---

### 📌 HẠNG MỤC 11: Nâng Cấp Toàn Diện Hệ Thống Đánh Giá Độ Hài Lòng Dịch Vụ CSAT (Customer Satisfaction Score)
- **Yêu cầu người dùng:** Triển khai tính năng đánh giá đơn CSAT (Customer Satisfaction Score) trực quan, tiện lợi, dễ tiếp cận tại các vị trí phù hợp trong hệ thống.
- **Phân tích & Hiện trạng:**
  - Trước đây, đánh giá CSAT bị giấu trong thẻ `<select>` thu nhỏ ở chân cột bên phải trang `detail.html`, chỉ hiển thị khi `RESOLVED` và biến mất hoàn toàn khi vé chuyển sang `CLOSED`.
  - Không có giao diện xem lại điểm CSAT đã chấm (1-5 sao); sinh viên không thấy điểm số sau khi đánh giá.
  - Trang danh sách cá nhân (`my-tickets.html`) và bàn làm việc Cán bộ (`staff-dashboard.html`) không hiển thị cột CSAT, khiến người dùng và cán bộ khó nắm bắt chất lượng tư vấn.
- **Giải pháp triển khai trọn gói:**
  1. **Backend Service (`TicketService.java`):**
     - Nâng cấp `closeTicket(ticketId, userId, rating, feedback)`: Ghi nhận cả số sao và lời góp ý của sinh viên vào dòng lịch sử `TicketHistory`.
     - Bổ sung `rateTicket(ticketId, userId, rating, feedback)`: Cho phép sinh viên chấm điểm CSAT linh hoạt trên vé đã `RESOLVED` hoặc `CLOSED` mà không bị gò bó.
  2. **Controller Web & REST (`TicketWebController`, `TicketRestController`):**
     - Mở endpoint `POST /tickets/{ticketId}/rate` và `POST /api/v1/tickets/{ticketId}/rate`.
     - Nhận tham số `rating` (1-5) và `feedback` (ý kiến đóng góp tùy chọn), bảo vệ phân quyền theo `@deptSecurity.canAccessTicket(#ticketId)`.
  3. **Tối ưu DTO & Projection Query (`TicketSummaryDto`, `TicketRepository`):**
     - Bổ sung trường `rating` vào `TicketSummaryDto` và 6 câu truy vấn JPQL constructor expression (`findSummaryByDepartmentId`, `findSummaryByCreatorId`, `findAllSummaries`, v.v.), loại bỏ hoàn toàn N+1 query.
  4. **Giao diện Khảo Sát CSAT Chi Tiết (`ticket/detail.html`):**
     - **Thẻ Khảo Sát CSAT Trọng Tâm (`#csatSection`)**: Đặt ngay luồng đọc chính dưới khung trao đổi.
     - **Trạng thái đã đánh giá**: Hiển thị bảng vàng vinh danh gồm 5 sao trực quan `★★★★★`, điểm số X/5 sao và thông điệp cảm ơn trang trọng.
     - **Trạng thái chưa đánh giá**: 5 ngôi sao tương tác hover/click mượt mà, nhãn hiển thị theo thời gian thực (1 sao - Không hài lòng → 5 sao - Rất hài lòng), ô góp ý bổ sung tùy chọn, 2 nút gửi: *Gửi Đánh Giá CSAT* (AJAX Zero Refresh) hoặc *Hài Lòng & Đóng Yêu Cầu*.
     - **Thẻ Sidebar Phụ Trợ**: Hiển thị khối CSAT Rated Box hoặc nút kêu gọi *"Chấm Điểm Đánh Giá Ngay"* cuộn mượt đến form khảo sát.
  5. **Bảng Danh Sách Vé Của Tôi (`ticket/my-tickets.html`):**
     - Thêm cột *"Đánh Giá CSAT"* trực tiếp trong bảng: Hiển thị huy hiệu `★ X / 5` nếu đã chấm, nút `Đánh giá ngay` màu vàng nổi bật nếu vé đã giải quyết/đóng nhưng chưa đánh giá.
  6. **Bảng Dashboard Cán Bộ (`ticket/staff-dashboard.html`):**
     - Bổ sung cột CSAT trực tiếp trên bảng vé của Cán bộ để theo dõi độ hài lòng của người học theo từng yêu cầu.
### 📌 HẠNG MỤC 12: Trang Cập Nhật Tài Khoản Cá Nhân & Quản Lý Hồ Sơ Từ Menu Góc Phải (`/profile`)
- **Yêu cầu người dùng:** Phần tài khoản góc phải chưa có chức năng cập nhật tài khoản của chính mình.
- **Thực trạng trước đó:**
  - Menu góc phải (`#userDropdown`) chỉ hiển thị: Tên tài khoản, Vai trò, Ticket của tôi, Bàn cán bộ, Đăng xuất. Hoàn toàn không có đường dẫn hay trang để người dùng xem và cập nhật thông tin cá nhân của mình.
  - Người dùng không thể đổi họ tên, số điện thoại, avatar hay đổi mật khẩu.
- **Giải pháp triển khai:**
  1. **DTOs Chuyên Biệt:**
     - `UserProfileDto`: Chứa đầy đủ thông tin hồ sơ (username, fullName, email, phone, avatarUrl, roleName, roleDescription, departmentName, status, createdAt).
     - `UpdateProfileRequest`: Validation họ tên (bắt buộc, max 150), số điện thoại (tối đa 30), avatarUrl.
     - `ChangePasswordRequest`: Validation mật khẩu hiện tại, mật khẩu mới (min 6 ký tự), xác nhận mật khẩu mới.
  2. **Service Nghiệp Vụ (`ProfileService.java`):**
     - `getProfile(userId)`: Lấy thông tin user kết hợp `findByIdWithRoleAndDepartment` tránh N+1.
     - `updateProfile(userId, request)`: Cập nhật thông tin cá nhân và **đồng bộ làm mới ngay `SecurityContextHolder`** để hiển thị ngay tên mới trên giao diện mà không cần đăng nhập lại.
     - `changePassword(userId, request)`: Kiểm tra khớp mật khẩu xác nhận, mã hóa bcrypt kiểm tra mật khẩu hiện tại, chống đặt mật khẩu mới trùng mật khẩu cũ.
  3. **Controller Web & REST:**
     - `ProfileWebController.java`: Phục vụ route `GET /profile`, `POST /profile/update`, `POST /profile/change-password` với `@PreAuthorize("isAuthenticated()")`.
     - `ProfileRestController.java`: Mở endpoint REST `/api/v1/profile` và `/api/v1/profile/change-password` hỗ trợ gọi AJAX.
  4. **Giao Diện Chuẩn Academic UI/UX (`auth/profile.html`):**
     - Kế thừa `layout:decorate="~{layout/main}"`.
     - Header banner tông đen Deep Slate (`#111827`) - Crimson (`#A51C30`).
     - Cột trái: Card tổng quan thông tin, Avatar to có viền nổi bật, Huy hiệu vai trò, Đơn vị trực thuộc, Danh sách email, số điện thoại, ngày tham gia, và **4 avatar mẫu học đường chọn nhanh bằng 1 click**.
     - Cột phải: 2 Tab điều hướng:
       - **Tab 1 - Thông Tin Cá Nhân**: Form cập nhật họ tên, số điện thoại, đường dẫn avatar kèm tính năng xem trước ảnh trực tiếp. Khóa cố định Username và Email trường cấp để bảo toàn định danh học vụ.
       - **Tab 2 - Đổi Mật Khẩu**: Nút mắt ẩn/hiện mật khẩu, thanh đo độ mạnh mật khẩu (Yếu/Trung bình/Khá/Mạnh), kiểm tra xác nhận mật khẩu theo thời gian thực.
  5. **Menu Góc Phải (`layout/navbar.html`):**
     - Bổ sung mục **"Hồ Sơ & Cài Đặt Tài Khoản"** (`/profile`) nổi bật với icon bánh răng xanh ngay đầu danh sách menu người dùng.
- **Trạng thái:** ✅ Đã hoàn tất và kiểm thử thành công (5/5 tests PASS).

---

## 📅 3. BẢNG TỔNG HỢP CÁC FILE ĐÃ CHỈNH SỬA

| STT | File thay đổi | Nội dung can thiệp | Trạng thái |
|:---:|---|---|:---:|
| **1** | `BaoCao_KhaoSat_Va_ThietKe_QAUTE_Portal_ban1.docx` | Bỏ Activity Diagram, tinh giản UML Ch1/2, SD04 đính kèm văn bản, chuyển 21 bảng sang Trắng Đen | ✅ Hoàn tất |
| **2** | `src/main/java/com/school/counseling/module/integration/...` | Chuyển `VideoIntegrationService`, `NodejsWebhookController`, `VideoWebhookPayload` sang dạng chú thích | ✅ Hoàn tất |
| **3** | `src/main/java/com/school/counseling/module/feed/service/PostService.java` | Vô hiệu hóa video generator, bổ sung `unhidePost`, `countResolvedReports`, map DTO báo cáo mở rộng | ✅ Hoàn tất |
| **4** | `src/main/java/com/school/counseling/config/SecurityConfig.java` | Chặn Guest: chuyển `/api/v1/ai/**`, `/api/v1/tickets/**`, `/ws-chat/**` sang `authenticated()` | ✅ Hoàn tất |
| **5** | `src/main/java/com/school/counseling/module/ticket/controller/TicketWebController.java` | Thêm `@PreAuthorize("isAuthenticated()")` cho tạo vé, thêm endpoint `POST /tickets/{id}/rate` và hỗ trợ feedback khi close | ✅ Hoàn tất |
| **6** | `src/main/java/com/school/counseling/module/ticket/controller/TicketRestController.java` | Bổ sung REST endpoint `POST /api/v1/tickets/{id}/rate` và feedback cho close | ✅ Hoàn tất |
| **7** | `src/main/java/com/school/counseling/module/ticket/service/TicketService.java` | Bổ sung nghiệp vụ `rateTicket` và lưu lời góp ý feedback CSAT vào lịch sử ticket | ✅ Hoàn tất |
| **8** | `src/main/java/com/school/counseling/module/ticket/dto/TicketSummaryDto.java` | Bổ sung trường `rating` vào DTO tối ưu hiển thị danh sách | ✅ Hoàn tất |
| **9** | `src/main/java/com/school/counseling/module/ticket/repository/TicketRepository.java` | Cập nhật 6 JPQL constructor queries đưa trường `t.rating` vào `TicketSummaryDto` | ✅ Hoàn tất |
| **10** | `src/main/resources/templates/ticket/detail.html` | Tích hợp Thẻ CSAT trung tâm (`#csatSection`), 5 sao hover/click, textarea góp ý, zero-refresh AJAX, nâng cấp thẻ CSAT sidebar | ✅ Hoàn tất |
| **11** | `src/main/resources/templates/ticket/my-tickets.html` | Thêm cột "Đánh Giá CSAT" hiển thị sao hoặc nút "Đánh giá ngay" | ✅ Hoàn tất |
| **12** | `src/main/resources/templates/ticket/staff-dashboard.html` | Thêm cột CSAT trên bảng vé của Cán bộ theo dõi độ hài lòng | ✅ Hoàn tất |
| **13** | `src/main/resources/templates/layout/navbar.html` | Thêm mục "Hồ Sơ & Cài Đặt Tài Khoản" (`/profile`) trong dropdown người dùng góc phải | ✅ Hoàn tất |
| **14** | `src/main/resources/templates/auth/profile.html` | Giao diện quản lý hồ sơ cá nhân, đổi họ tên, số điện thoại, avatar mẫu, đổi mật khẩu | ✅ Hoàn tất |
| **15** | `src/main/java/com/school/counseling/module/auth/service/ProfileService.java` | Nghiệp vụ lấy hồ sơ, cập nhật thông tin cá nhân, đổi mật khẩu an toàn | ✅ Hoàn tất |
| **16** | `src/main/java/com/school/counseling/module/auth/controller/ProfileWebController.java` & `ProfileRestController.java` | Web & REST Controller cho module quản lý tài khoản cá nhân | ✅ Hoàn tất |
| **17** | `src/main/java/com/school/counseling/module/auth/dto/...` | Các DTO: `UserProfileDto`, `UpdateProfileRequest`, `ChangePasswordRequest` | ✅ Hoàn tất |
| **18** | `src/main/resources/templates/layout/main.html` | Thêm Toast container góc trên phải (`#toastContainer`), auto-hide 4s, xóa alert in-page | ✅ Hoàn tất |
| **19** | `src/main/java/com/school/counseling/module/feed/entity/PostReport.java` & `Post.java` | Thêm `@NotFound(action = NotFoundAction.IGNORE)` chống lỗi 500 khi bài viết bị soft-delete | ✅ Hoàn tất |
| **20** | `src/main/resources/templates/moderation/reports-list.html` | Xóa alert duplicate, thêm Tabs lọc, modal xem bài ẩn, nút khôi phục | ✅ Hoàn tất |
| **21** | `src/test/java/...` | 26/26 tests PASS: ProfileWebControllerTest (5/5), TicketDepartmentIsolationTest (4/4), ModerationControllerTest (3/3), PostServiceTest (9/9), RagChatRestControllerTest (5/5) | ✅ Hoàn tất |

---

## 🧪 4. KẾT QUẢ KIỂM THỬ TỔNG HỢP

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in ProfileWebControllerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in TicketDepartmentIsolationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in RagChatRestControllerTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in ModerationControllerTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in PostServiceTest
[INFO] Total Tests: 26, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```