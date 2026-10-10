# TIẾN ĐỘ 16: TÁI CẤU TRÚC KIẾN TRÚC HỆ THỐNG (ARCHITECTURAL REFACTORING), XÓA BỎ GOD SERVICES & TỐI ƯU HÓA HIỆU NĂNG TƯƠNG TÁC CONCURRENCY

> **Dành cho Sinh viên IT & Vibecoder Java Core:**  
> Tài liệu này tổng hợp toàn bộ kế hoạch và tiến độ "đại tu" kiến trúc mã nguồn của dự án `doancuoiki_demo1`. Từng khái niệm kỹ thuật được giải thích trực quan, dễ hiểu bằng hình tượng đời sống, giúp bạn không chỉ code chạy được mà còn hiểu bản chất cách xây dựng hệ thống chịu tải cao, sạch đẹp và đúng chuẩn Enterprise.

---

## 🎯 1. TỔNG QUAN BỐI CẢNH & NGUYÊN DO CẦN TÁI CẤU TRÚC (ARCHITECTURAL DEBT)

Trước đây, hệ thống hoạt động ổn ở quy mô nhỏ (vài chục người dùng thử nghiệm), nhưng khi phân tích kỹ lưỡng kiến trúc phục vụ toàn trường HCMUTE, mã nguồn bộc lộ 4 điểm nghẽn nghiêm trọng:

1. **Phụ thuộc chặt (Tightly Coupled) & Nguy cơ nghẽn Thread Tomcat:**
   - *Hiện tượng:* Khi Sinh viên tạo Ticket, hàm `TicketService.createTicket()` gọi trực tiếp `EmailAsyncService.sendEmail()`.
   - *Nguy cơ:* Nếu mạng chập chờn hoặc hàng đợi mail bị đầy, luồng xử lý web của Tomcat phải chờ đợi hoặc giữ Transaction Database lâu hơn cần thiết.
   - *Hình tượng hóa:* Giống như bạn đi ăn phở, người thu ngân vừa thu tiền xong phải tự tay chạy ra bưu điện gửi thư báo cho sếp rồi mới quay lại phục vụ khách tiếp theo $\rightarrow$ Quán ăn nhanh chóng kẹt cứng hàng dài!

2. **Khái niệm "God Service" (Một Service ôm đồm tất cả):**
   - *Hiện tượng:* `PostService.java` phình to hơn 850 dòng code, giải quyết từ đăng bài công văn, diễn đàn sinh viên, quản lý bình luận phân cấp, thả tim, đến xử lý khiếu nại báo cáo vi phạm.
   - *Hậu quả:* Vi phạm nguyên lý *Single Responsibility Principle (SRP)* trong SOLID. Mỗi lần sửa một tính năng nhỏ của bình luận lại có nguy cơ làm lỗi logic duyệt bài công văn.

3. **Rủi ro Race Condition (Tranh chấp tài nguyên) khi Spam Click:**
   - *Hiện tượng:* Khi nhiều sinh viên cùng bấm "Thích" (Like) một bài viết hoặc cán bộ cùng bấm "Tiếp nhận" (Claim) một Ticket cùng 1 tích tắc.
   - *Hậu quả:* Số lượt like bị lệch, hoặc 2 cán bộ cùng lúc được hệ thống báo là đã tiếp nhận thành công cùng 1 Ticket.

4. **Kỷ luật `spring.jpa.open-in-view: false` & Nguy cơ văng lỗi `LazyInitializationException`:**
   - *Hiện tượng:* Tắt OSIV để giải phóng kết nối Database về Connection Pool ngay khi Service kết thúc. Tuy nhiên, nếu Controller hoặc View Thymeleaf vô tình gọi `ticket.getAttachments().size()`, chương trình sẽ sập ngay lập tức vì Session Hibernate đã đóng.

---

## 🛠️ 2. LỘ TRÌNH TÁI CẤU TRÚC THEO TỪNG GIAI ĐOẠN

```
[ Giai đoạn 1: ĐÃ XONG ] ──> [ Giai đoạn 2: SẮP TỚI ] ──> [ Giai đoạn 3: DỰ KIẾN ] ──> [ Giai đoạn 4: MỞ RỘNG ]
  Domain Event & Mail         Tách God Service Post        Bảo mật IDOR SpEL             Live Chat to Ticket
  Non-blocking Ticket         Concurrency Safe Like        DTO Strict (OSIV=false)       WebSocket Handover
```

---

### ✅ GIAI ĐOẠN 1 (ĐÃ HOÀN THÀNH): TÁCH RỜI LUỒNG GỬI MAIL BẰNG DOMAIN EVENTS

#### 1. Khái niệm trực quan:
- Thay vì tự tay gửi mail, `TicketService` chỉ đóng vai trò **"Phát loa thông báo"** (*Publish Event*): *"Có vé mới mã số #123 vừa được tạo!"*.
- `TicketEventListener` đứng ở góc phòng nghe thấy tiếng loa sẽ tự động nhặt thông tin và đem đi gửi mail ở luồng ngầm riêng (`@Async("mailTaskExecutor")`), hoàn toàn không làm gián đoạn luồng trả kết quả về cho sinh viên.

#### 2. Các tệp đã triển khai:
- **Tạo các Domain Event POJO (`com.school.counseling.module.ticket.event`):**
  - `TicketCreatedEvent.java`: Mang thông tin `ticketId`, `ticketCode`, `title`, `studentEmail`, `departmentName`, `priority`.
  - `TicketClaimedEvent.java`: Mang thông tin cán bộ tiếp nhận và người tạo vé.
  - `TicketResolvedEvent.java`: Mang thông tin kết quả giải quyết và thời gian hoàn tất.
- **Tạo Event Listener độc lập:**
  - `TicketEventListener.java`: Lắng nghe bằng `@EventListener`, chạy ngầm bằng `@Async("mailTaskExecutor")`, bắt lỗi try-catch độc lập giúp hệ thống gửi mail thất bại thì Ticket vẫn được lưu trọn vẹn.
- **Refactor `TicketService.java`:**
  - Loại bỏ hoàn toàn sự phụ thuộc trực tiếp vào `EmailAsyncService`.
  - Tiêm `ApplicationEventPublisher` và gọi `eventPublisher.publishEvent(...)`.
- **Tách rời Web Controller:**
  - `StaffTicketWebController.java` & `TicketWebController.java` không còn gọi mail thủ công, code ngắn hơn và chuẩn MVC.
- **Kiểm thử tự động (TDD Green Phase):**
  - Viết bộ kiểm thử [TicketDomainEventTest.java](file:///d:/HK5/LapTrinhWeb/doancuoiki/doancuoiki_demo1/src/test/java/com/school/counseling/module/ticket/TicketDomainEventTest.java).
  - Kết quả: `mvn test -Dtest=TicketDomainEventTest` $\rightarrow$ **BUILD SUCCESS (100% Pass)**.

---

### ⏳ GIAI ĐOẠN 2 (KẾ HOẠCH TIẾP THEO): CHIA TÁCH GOD SERVICE `PostService` & CHỐNG RACE CONDITION LƯỢT THÍCH

#### 1. Bài toán và Mục tiêu:
- Bẻ nhỏ `PostService.java` (853 dòng) thành 3 Service chuyên biệt, mỗi Service chỉ làm đúng 1 nhiệm vụ.
- Đảm bảo tính toán toàn vẹn khi hàng trăm sinh viên thả tim đồng thời trong các bài viết sôi nổi trên diễn đàn.

#### 2. Kế hoạch chia tách 3 Services:

| Service Mới | Trách nhiệm chuyên biệt | Các phương thức chuyển giao |
| :--- | :--- | :--- |
| **`PostService`** | Quản lý vòng đời bài viết | - `createOfficialPost`, `createForumPost`<br>- `updatePost`, `deletePost`<br>- `getOfficialFeed`, `getForumFeed`<br>- `togglePinPost`, `changePostStatus` |
| **`CommentService`** | Quản lý bình luận & Thảo luận | - `addComment(postId, parentCommentId, content)`<br>- `getCommentsByPost(postId)` (xếp cây phân cấp cha-con)<br>- `deleteComment(commentId)` |
| **`ModerationService`** | Kiểm duyệt nội dung & Xử lý tố cáo | - `reportPost(postId, reason)`<br>- `getPendingReports()`, `resolveReport()`<br>- `hideViolatingPost()`, `autoFilterSensitiveWords()` |

#### 3. Chống Race Condition cho Lượt Thích (Like/Reaction):
- **Vấn đề "Thả tim ma":** Sinh viên mở 2 tab hoặc click liên tục nút Tim, 2 request đọc `post.likeCount = 10` cùng lúc, cả 2 cùng cộng 1 thành `11` thay vì `12`.
- **Giải pháp kỹ thuật chuẩn Enterprise:**
  1. Thêm chỉ mục duy nhất vào bảng `post_likes` ở tầng Database:
     ```sql
     ALTER TABLE post_likes ADD CONSTRAINT uk_post_user UNIQUE (post_id, user_id);
     ```
  2. Dùng câu lệnh cập nhật nguyên tử (*Atomic DB Update*):
     ```sql
     UPDATE posts SET like_count = like_count + 1 WHERE id = :postId;
     ```
     *(Cơ sở dữ liệu tự khóa mức hàng - row-level lock trong vài mili-giây, không bao giờ mất dữ liệu dù có spam click).*

---

### ⏳ GIAI ĐOẠN 3 (KẾ HOẠCH DỰ KIẾN): SIẾT CHẶT PHÂN QUYỀN ĐA PHÒNG BAN (IDOR) & DTO TRUYỀN DỮ LIỆU

#### 1. Bài toán Bảo mật Đa phòng ban (IDOR - Insecure Direct Object References):
- HCMUTE có 5 đơn vị riêng biệt:
  1. Phòng Đào tạo (ĐT)
  2. Phòng Chăm sóc & Hỗ trợ Người học (CTSV)
  3. Phòng Kế hoạch - Tài chính (KHTC)
  4. Phòng Khảo thí & Đảm bảo Chất lượng (KT&ĐBCL)
  5. Khoa Công nghệ Thông tin (CNTT)
- *Rủi ro:* Cán bộ phòng Đào tạo nếu biết ID của một Ticket thuộc phòng Tài chính thì không được phép xem nội dung hoặc bấm giải quyết vé đó!
- *Giải pháp:* Rà soát và phủ kín biểu thức SpEL trên Controller:
  ```java
  @PreAuthorize("hasRole('ADMIN') or @deptSecurity.canAccessTicket(#id)")
  @GetMapping("/tickets/{id}")
  public String viewTicketDetail(@PathVariable Long id, ...) { ... }
  ```

#### 2. Kỷ luật DTO sạch để không bao giờ bị lỗi `LazyInitializationException`:
- Do hệ thống đã cấu hình:
  ```yaml
  spring:
    jpa:
      open-in-view: false
  ```
- *Quy tắc sống còn:* 
  - Mọi dữ liệu truyền ra `@Controller` (`model.addAttribute(...)`) hoặc `@RestController` (`ApiResponse<T>`) **BẮT BUỘC 100%** phải là DTO/Record được ánh xạ trọn vẹn bên trong phạm vi `@Transactional` của Service.
  - Tuyệt đối không truyền thẳng Hibernate Entity ra ngoài View.

---

### ⏳ GIAI ĐOẠN 4 (DỰ KIẾN MỞ RỘNG): PHÂN BIỆT CHATBOT RAG & LIVE CHAT VÀ NÚT "CHUYỂN THÀNH TICKET"

Nhiều bạn mới vào dự án thường nhầm lẫn giữa **Chatbot AI** và **Live Chat Cán bộ**. Bản thiết kế phân định rạch ròi như sau:

```
                  ┌──────────────────────────────────────────────┐
                  │          SINH VIÊN TRÊN HỆ THỐNG            │
                  └──────┬────────────────────────────────┬──────┘
                         │                                │
       [Hỏi đáp tự động 24/7]                 [Hành chính: 7h30 - 16h30]
                         │                                │
                         ▼                                ▼
              ┌─────────────────────┐          ┌─────────────────────┐
              │ CHATBOT AI TRỢ LÝ   │          │ LIVE CHAT TRỰC TIẾP │
              │ (RagChatbotService) │          │    (ChatService)    │
              └──────────┬──────────┘          └──────────┬──────────┘
                         │                                │
            [Không tìm thấy đáp án]              [Hết giờ / Việc phức tạp]
                         │                                │
                         ▼                                ▼
            ┌────────────────────────────────────────────────────────┐
            │       NÚT: "TẠO TICKET HỖ TRỢ / CHUYỂN THÀNH TICKET"    │
            │           (TicketService.convertConversationToTicket)  │
            └────────────────────────────────────────────────────────┘
```

1. **Chatbot AI (`RagChatbotService`):**
   - Đặt tại Widget góc màn hình (`/api/v1/ai/chat`).
   - Tự động tra cứu quy chế, sổ tay sinh viên bằng Gemini Flash & vector similarity. Hoạt động 24/7 không cần người trực.
2. **Live Chat Trực Tuyến (`ChatService` & WebSocket STOMP):**
   - Sinh viên chat người-thật với Cán bộ phòng ban trong giờ hành chính qua kênh WebSocket `/topic/chat/{conversationId}`.
3. **Nút "Chuyển thành Ticket" (`convertConversationToTicket`):**
   - Khi hết giờ làm việc hoặc vụ việc cần thẩm tra hồ sơ lâu dài, Cán bộ/Sinh viên bấm nút.
   - Hệ thống tự động đóng phiên chat, gom toàn bộ lịch sử tin nhắn và tệp đính kèm chuyển hóa thành 1 `Ticket` chính thức có mã số, gắn SLA đếm ngược (24h/48h/72h) để đảm bảo không bị bỏ quên việc.

---

## 📋 3. MA TRẬN PHÂN CHIA TRÁCH NHIỆM (DEV A vs DEV B)

Tuân thủ nghiêm ngặt quy định tại `AGENTS.md`:

| Trách nhiệm của Dev A (Backend Lead - Đang phụ trách) | Ranh giới của Dev B (Frontend Lead - Tuyệt đối không can thiệp) |
| :--- | :--- |
| ✅ Thiết kế Entity, Repository, Migrations, Constraints. | ❌ Cấu trúc giao diện HTML (`templates/**`). |
| ✅ Viết Service logic, Domain Events, Async Queue. | ❌ Phong cách CSS, SCSS, Theme HCMUTE (`static/css/**`). |
| ✅ Phân quyền SpEL `@deptSecurity`, Security Filter. | ❌ Thao tác DOM, Animation, JS Client (`static/js/**`). |
| ✅ Thiết kế DTOs, Controllers, kiểm soát Model attributes. | ❌ WebSocket STOMP Client, Chart.js, Cloudinary upload UI. |

---

## 🎯 4. TỔNG KẾT & BƯỚC HÀNH ĐỘNG TIẾP THEO

1. **Bước 1 (Đã đạt):** Tách Domain Event cho Ticket và hoàn thành Unit Test với kết quả xanh (Pass).
2. **Bước 2 (Chuẩn bị tiến hành):** 
   - Viết bộ kiểm thử TDD cho `CommentService` và `ModerationService`.
   - Tiến hành tách mã nguồn từ `PostService.java`.
   - Thêm ràng buộc Database và viết query cập nhật lượt thích an toàn đa luồng.
3. Toàn bộ quá trình tiếp tục được thực hiện trên nhánh tính năng và chỉ merge khi có lệnh của bạn!
